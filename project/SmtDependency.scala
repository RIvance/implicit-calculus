import sbt._
import sbt.Keys._

import java.io.{InputStream, OutputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.security.{DigestOutputStream, MessageDigest}
import java.util.zip.ZipFile
import scala.sys.process.Process

/** Official, checksum-pinned Java/JNI distribution, installed only in this project's target directory. */
object SmtDependency {
  val distribution = taskKey[File]("Download and verify the project's official Z3 Java/native dependency")
  private val version = "4.14.1"

  // SHA-256 of the exact archives from https://github.com/Z3Prover/z3/releases/tag/z3-4.14.1.
  private val checksums = Map(
    "x64-glibc-2.35" -> "76f797c849ae1487ce08840321d3334720434e0eb1a23e1bd755c3818ffb7265",
    "arm64-glibc-2.34" -> "63655c7b81ea54298494bc7c47d65ed5e1b7ea171fd91e78255b0cc3e214e1c2",
    "x64-osx-13.7.4" -> "08df09e74096470742e78eb222b6b5772524b926e2d1bea8b7c6647db1cefdd5",
    "arm64-osx-13.7.4" -> "fe931dc771bfe2896901b2c3031ad973040ac5606a66d0ac567e84dc1aaf5647",
    "x64-win" -> "a73cf13b6bef3b1b9902144747083651e1bf40af997732a123a86dda4e7abeb5",
    "x86-win" -> "30ee7ce422e1e6d5f9fa04d3af34cf24c1b021e00b898cfe371358a206629513"
  )

  private def releasePlatform: String = {
    val system = sys.props("os.name").toLowerCase(java.util.Locale.ROOT)
    val architecture = sys.props("os.arch").toLowerCase(java.util.Locale.ROOT)
    val machine = architecture match {
      case "amd64" | "x86_64" => "x64"
      case "aarch64" | "arm64" => "arm64"
      case "x86" | "i386" => "x86"
      case _ => sys.error(s"No pinned official Z3 Java distribution for $system/$architecture")
    }
    if (system.contains("linux") && machine == "x64") "x64-glibc-2.35"
    else if (system.contains("linux") && machine == "arm64") "arm64-glibc-2.34"
    else if (system.contains("mac") && machine != "x86") s"$machine-osx-13.7.4"
    else if (system.contains("windows") && machine != "arm64") s"$machine-win"
    else sys.error(s"No pinned official Z3 Java distribution for $system/$architecture")
  }

  /** Consumes and closes the stream, including on failure. */
  private def sha256(input: InputStream): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    try input.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest))
    finally input.close()
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
  }

  private def verify(archive: File, expected: String): Unit = {
    val actual = sha256(Files.newInputStream(archive.toPath))
    if (actual != expected) {
      sys.error(s"Z3 checksum mismatch for $archive: expected $expected, received $actual")
    }
  }

  private def nativeEnvironment(binary: File): Map[String, String] = {
    val system = sys.props("os.name").toLowerCase(java.util.Locale.ROOT)
    val variable = if (system.contains("windows")) "PATH"
      else if (system.contains("mac")) "DYLD_LIBRARY_PATH"
      else "LD_LIBRARY_PATH"
    val inherited = sys.env.collectFirst { case (key, value) if key.equalsIgnoreCase(variable) => value }.toSeq
    // A compiler-provided runtime can live outside the loader's default directories (for example on Nix).
    // Query its location without installing anything or changing the parent process's environment.
    val runtime = if (system.contains("linux")) {
      val compiler = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator)
        .map(directory => file(directory) / "c++").find(_.canExecute)
      compiler.toSeq.flatMap { executable =>
        val library = file(Process(Seq(executable.getAbsolutePath, "-print-file-name=libstdc++.so.6")).!!.trim)
        if (library.isAbsolute && library.isFile) Seq(library.getParent) else Seq.empty
      }
    } else Seq.empty
    Map(variable -> (Seq(binary.getAbsolutePath) ++ inherited ++ runtime).mkString(java.io.File.pathSeparator))
  }

  val settings: Seq[Def.Setting[_]] = Seq(
    distribution := {
      val logger = streams.value.log
      val platform = releasePlatform
      val name = s"z3-$version-$platform"
      val directory = target.value / "z3"
      val archive = directory / s"$name.zip"
      val expected = checksums(platform)
      IO.createDirectory(directory)
      if (!archive.isFile) {
        val partial = directory / s"$name.zip.part"
        logger.info(s"Downloading official Z3 $version into $directory")
        try {
          val source = url(s"https://github.com/Z3Prover/z3/releases/download/z3-$version/$name.zip")
          val connection = source.openConnection()
          connection.setConnectTimeout(30000)
          connection.setReadTimeout(120000)
          val input = connection.getInputStream
          try Files.copy(input, partial.toPath, StandardCopyOption.REPLACE_EXISTING)
          finally input.close()
          verify(partial, expected)
          IO.move(partial, archive)
        } finally IO.delete(partial)
      } else verify(archive, expected)

      val libraries = if (platform.endsWith("-win")) Seq("libz3.dll", "libz3java.dll")
        else Seq(System.mapLibraryName("z3"), System.mapLibraryName("z3java"))
      val installed = directory / name
      val binary = installed / "bin"
      IO.createDirectory(binary)
      val zip = new ZipFile(archive)
      try {
        ("com.microsoft.z3.jar" +: libraries).foreach { filename =>
          val entry = Option(zip.getEntry(s"$name/bin/$filename"))
            .getOrElse(sys.error(s"Official Z3 archive lacks $filename"))
          val destination = binary / filename
          val unchanged = destination.isFile &&
            sha256(Files.newInputStream(destination.toPath)) == sha256(zip.getInputStream(entry))
          if (!unchanged) {
            val input = zip.getInputStream(entry)
            try Files.copy(input, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
            finally input.close()
          }
        }
      } finally zip.close()
      installed
    },
    Compile / unmanagedJars += Attributed.blank(distribution.value / "bin" / "com.microsoft.z3.jar"),
    Compile / run / javaOptions += s"-Djava.library.path=${(distribution.value / "bin").getAbsolutePath}",
    Test / javaOptions += s"-Djava.library.path=${(distribution.value / "bin").getAbsolutePath}",
    Compile / run / envVars ++= nativeEnvironment(distribution.value / "bin"),
    Test / envVars ++= nativeEnvironment(distribution.value / "bin")
  )
}
