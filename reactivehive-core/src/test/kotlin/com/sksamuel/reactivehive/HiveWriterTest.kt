package com.sksamuel.reactivehive

import arrow.core.Try
import com.sksamuel.reactivehive.formats.ParquetFormat
import com.sksamuel.reactivehive.parquet.parquetReader
import com.sksamuel.reactivehive.parquet.readAll
import com.sksamuel.reactivehive.partitioners.DynamicPartitioner
import io.kotlintest.matchers.collections.shouldBeEmpty
import io.kotlintest.shouldBe
import io.kotlintest.specs.FunSpec
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.metastore.TableType
import org.apache.hadoop.hive.metastore.api.Database
import org.apache.hadoop.hive.metastore.api.FieldSchema

class HiveWriterTest : FunSpec(), HiveTestConfig {

  init {

    val schema = StructType(
        StructField("name", StringType),
        StructField("title", StringType),
        StructField("salary", Float64Type),
        StructField("employed", BooleanType)
    )

    val users = listOf(
        Struct(schema, "sam", "mr", 100.43, false),
        Struct(schema, "ben", "mr", 230.523, false),
        Struct(schema, "tom", "mr", 60.98, true),
        Struct(schema, "laura", "ms", 421.512, true),
        Struct(schema, "kelly", "ms", 925.162, false)
    )

    Try {
      client.createDatabase(Database("tests", null, "/user/hive/warehouse/tests", emptyMap()))
    }

    test("write to a non partitioned table") {

      val writer = HiveWriter(
          DatabaseName("tests"),
          TableName("employees"),
          WriteMode.Overwrite,
          fileManager = OptimisticFileManager(ConstantFileNamer("test.pq")),
          createConfig = CreateTableConfig(schema, null, TableType.MANAGED_TABLE, ParquetFormat),
          client = client,
          fs = fs
      )
      writer.write(users)
      writer.close()

      val table = HiveUtils(client).table(DatabaseName("tests"), TableName("employees"))

      table.sd.cols shouldBe listOf(
          FieldSchema("name", "string", null),
          FieldSchema("title", "string", null),
          FieldSchema("salary", "double", null),
          FieldSchema("employed", "boolean", null)
      )

      HiveUtils(client).table(DatabaseName("tests"), TableName("employees")).partitionKeys.shouldBeEmpty()

      val file = Path(table.sd.location, "test.pq")
      val reader = parquetReader(file, conf)
      val struct = reader.readAll().toList()
      struct.first().schema shouldBe StructType(
          StructField(name = "name", type = StringType, nullable = true),
          StructField(name = "title", type = StringType, nullable = true),
          StructField(name = "salary", type = Float64Type, nullable = true),
          StructField(name = "employed", type = BooleanType, nullable = true)
      )
      struct.map { it.values }.toList() shouldBe listOf(
          listOf("sam", "mr", 100.43, false),
          listOf("ben", "mr", 230.523, false),
          listOf("tom", "mr", 60.98, true),
          listOf("laura", "ms", 421.512, true),
          listOf("kelly", "ms", 925.162, false)
      )
    }

    test("write to a partitioned table") {
      val writer = HiveWriter(
          DatabaseName("tests"),
          TableName("employees"),
          WriteMode.Overwrite,
          DynamicPartitioner,
          OptimisticFileManager(ReactiveHiveFileNamer),
          createConfig = CreateTableConfig(schema, PartitionPlan(PartitionKey("title"))),
          client = client,
          fs = fs
      )
      writer.write(users)
      writer.close()

      HiveUtils(client).table(DatabaseName("tests"), TableName("employees")).sd.cols shouldBe listOf(
          FieldSchema("name", "string", null),
          FieldSchema("salary", "double", null),
          FieldSchema("employed", "boolean", null)
      )

      HiveUtils(client).table(DatabaseName("tests"), TableName("employees")).partitionKeys shouldBe listOf(
          FieldSchema("title", "string", null)
      )
    }

    test("setting table type of new tables") {

      for (tableType in listOf(TableType.EXTERNAL_TABLE, TableType.MANAGED_TABLE)) {
        Try {
          client.dropTable("tests", "employees4")
        }

        val createConfig = CreateTableConfig(schema, null, tableType, location = Path("/user/hive/warehouse/employees4"))

        val writer = HiveWriter(
            DatabaseName("tests"),
            TableName("employees4"),
            WriteMode.Overwrite,
            DynamicPartitioner,
            OptimisticFileManager(ReactiveHiveFileNamer),
            createConfig = createConfig,
            client = client,
            fs = fs
        )

        writer.write(users)
        writer.close()

        client.getTable("tests", "employees4").tableType shouldBe tableType.asString()
      }
    }

    test("partition fields should not be included in the data written to data files") {

      Try {
        client.dropTable("tests", "test10")
      }

      fun partitions() = client.listPartitions("tests", "test10", Short.MAX_VALUE)

      val createConfig = CreateTableConfig(
          schema,
          PartitionPlan(PartitionKey("title"))
      )

      val writer = HiveWriter(
          DatabaseName("tests"),
          TableName("test10"),
          WriteMode.Overwrite,
          DynamicPartitioner,
          OptimisticFileManager(ConstantFileNamer("test.pq")),
          createConfig = createConfig,
          client = client,
          fs = fs
      )

      writer.write(users)
      writer.close()

      Thread.sleep(2000)

      partitions().map { it.values } shouldBe listOf(listOf("mr"), listOf("ms"))
      partitions().forEach {
        val file = Path(it.sd.location, "test.pq")
        val reader = parquetReader(file, conf)
        val struct = reader.read()
        struct.schema shouldBe StructType(
            StructField(name = "name", type = StringType, nullable = true),
            StructField(name = "salary", type = Float64Type, nullable = true),
            StructField(name = "employed", type = BooleanType, nullable = true)
        )
      }
    }
  }
}