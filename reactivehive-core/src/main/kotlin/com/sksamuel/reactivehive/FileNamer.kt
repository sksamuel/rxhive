package com.sksamuel.reactivehive

import org.apache.hadoop.fs.Path
import java.util.*

/**
 * When creating new files in the Hadoop filestore, an implementation of this
 * interface is used to generate a file name.
 *
 * Users of reactive-hive can supply an implementation of this interface if they
 * wish to control the filename.
 */
interface FileNamer {
  /**
   * @param dir the directory that will contain the file
   */
  fun generate(dir: Path): String
}

/**
 * Default implementation of [FileNamer] which just generates a random filename, prefixed
 * with the name of this project.
 */
object ReactiveHiveFileNamer : FileNamer {
  override fun generate(dir: Path): String =
      "reactivehive_" + UUID.randomUUID().toString().replace("-", "")
}

object UUIDFileNamer : FileNamer {
  override fun generate(dir: Path): String = UUID.randomUUID().toString()
}