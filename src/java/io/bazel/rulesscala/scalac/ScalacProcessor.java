package io.bazel.rulesscala.scalac;

import io.bazel.rulesscala.jar.JarCreator;
import io.bazel.rulesscala.worker.GenericWorker;
import io.bazel.rulesscala.worker.Processor;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import scala.tools.nsc.Driver;
import scala.tools.nsc.MainClass;
import scala.tools.nsc.reporters.ConsoleReporter;

class ScalacProcessor implements Processor {
  /**
   * This is the reporter field for scalac, which we want to access
   */
  private static Field reporterField;
  static {
    try {
      reporterField = Driver.class.getDeclaredField("reporter"); //NoSuchFieldException
      reporterField.setAccessible(true);
    }
    catch (NoSuchFieldException ex) {
      throw new RuntimeException("could not access reporter field on Driver", ex);
    }
  }

  private void digestFile(MessageDigest md, File file) throws Exception {
      // logging for debugging
      {
          MessageDigest localMd = MessageDigest.getInstance("SHA");
          RandomAccessFile aFile = new RandomAccessFile(file, "r");
          FileChannel inChannel = aFile.getChannel();
          MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());
          buffer.load();
          localMd.update(buffer);
          buffer.clear();
          inChannel.close();
          aFile.close();
          String sha1 = bytesToHex(md.digest());
          System.out.println("[digest]" + file + ": " + sha1);
      }
      RandomAccessFile aFile = new RandomAccessFile(file, "r");
      FileChannel inChannel = aFile.getChannel();
      MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());
      buffer.load();
      md.update(buffer);
      buffer.clear();
      inChannel.close();
      aFile.close();
  }

  private void digestFileAtPath(MessageDigest md, String path) throws Exception {
      File file = new File(path);
      digestFile(md, file);
  }

  private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte byt : bytes)
            result.append(Integer.toHexString(0xFF & byt)).substring(1);
      return result.toString();
  }

  private void digestFileCollections(MessageDigest md, List<File> ... fileCollections) throws Exception {
      for (List<File> fileCollection : fileCollections) {
          for (File file : fileCollection) {
              digestFile(md, file);
          }
      }
  }

  @Override
  public void processRequest(List<String> args) throws Exception {
    Path tmpPath = null;
    try {
      CompileOptions ops = new CompileOptions(args);

      MessageDigest md = MessageDigest.getInstance("SHA");

      Path outputPath = FileSystems.getDefault().getPath(ops.outputName);
      tmpPath = Files.createTempDirectory(outputPath.getParent(), "tmp");

      List<File> jarFiles = extractSourceJars(ops, outputPath.getParent());
      List<File> scalaJarFiles = filterFilesByExtension(jarFiles, ".scala");
      List<File> javaJarFiles = filterFilesByExtension(jarFiles, ".java");

      digestFileCollections(md, jarFiles, scalaJarFiles, javaJarFiles);

      String[] scalaSources = collectSrcJarSources(ops.files, scalaJarFiles, javaJarFiles);

      for (String scalaSource : scalaSources) {
          digestFileAtPath(md, scalaSource);
      }

      String[] javaSources = GenericWorker.appendToString(ops.javaFiles, javaJarFiles);
      for (String javaSource : javaSources) {
          digestFileAtPath(md, javaSource);
      }
      if (scalaSources.length == 0 && javaSources.length == 0) {
        throw new RuntimeException("Must have input files from either source jars or local files.");
      }

      Path cacheDir = Paths.get("/Users/gkk/tmp/scalac-worker-cache");
        String sha1 = bytesToHex(md.digest());
        System.out.println(sha1);
      String cacheKey = sha1;
      File cacheKeyOnDisk = Paths.get(cacheDir.toString(), cacheKey).toFile();

      File cacheTmpDirOnDisk = Paths.get(cacheKeyOnDisk.toString(), "scalac_output").toFile();
      Path cacheStatsFileOnDisk = Paths.get(cacheKeyOnDisk.toString(), "stats_file");


      /**
       * Compile scala sources if available (if there are none, we will simply
       * compile java sources).
       */
        if (cacheKeyOnDisk.exists()) {
            System.out.println("The " + cacheKeyOnDisk + " exists");
            tmpPath.toFile().delete();
            copyFolder(cacheTmpDirOnDisk, tmpPath.toFile());
            Files.copy(cacheStatsFileOnDisk, Paths.get(ops.statsfile));
        } else {
            if (scalaSources.length > 0) {
                compileScalaSources(ops, scalaSources, tmpPath);
            }
            try {
                cacheKeyOnDisk.mkdir();
                copyFolder(tmpPath.toFile(), cacheTmpDirOnDisk);
                Files.copy(Paths.get(ops.statsfile), cacheStatsFileOnDisk);
            } catch (Exception e) {
                if (cacheKeyOnDisk.exists())
                    removeTmp(cacheKeyOnDisk.toPath());
                throw e;
            }
        }

      /**
       * Copy the resources
       */
      copyResources(ops.resourceFiles, ops.resourceStripPrefix, tmpPath);

      /**
       * Extract and copy resources from resource jars
       */
      copyResourceJars(ops.resourceJars, tmpPath);

      /**
       * Copy classpath resources to root of jar
       */
      copyClasspathResourcesToRoot(ops.classpathResourceFiles, tmpPath);

      /**
       * Now build the output jar
       */
      String[] jarCreatorArgs = {
        "-m",
        ops.manifestPath,
        outputPath.toString(),
        tmpPath.toString()
      };
      JarCreator.buildJar(jarCreatorArgs);

      /**
       * Now build the output ijar
       */
      if(ops.iJarEnabled) {
        Process iostat = new ProcessBuilder()
          .command(ops.ijarCmdPath, ops.outputName, ops.ijarOutput)
          .inheritIO()
          .start();
        int exitCode = iostat.waitFor();
        if(exitCode != 0) {
          throw new RuntimeException("ijar process failed!");
        }
      }
    }
    finally {
        if (tmpPath.toFile().exists())
            removeTmp(tmpPath);
    }
  }

    private  void copyFolder(File src, File dest) throws IOException {
//      System.out.println("copyFolder " + src + " " + dest);
        Path absDest = dest.toPath().toAbsolutePath();
        try (Stream<Path> stream = Files.walk(src.toPath())) {
            stream.forEach(sourcePath -> {

                try {
                    Path target = absDest.resolve(src.toPath().relativize(sourcePath));
//                    System.out.println("Copy " + absSrc + " to " + " " + target);
                    Files.copy(
                            /*Source Path*/
                            sourcePath,
                            /*Destination Path */
                            target);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            });

        }

    }

  private static String[] collectSrcJarSources(String[] files, List<File> scalaJarFiles, List<File> javaJarFiles) {
    String[] scalaSources = GenericWorker.appendToString(files, scalaJarFiles);
    return GenericWorker.appendToString(scalaSources, javaJarFiles);
  }

  private static List<File> filterFilesByExtension(List<File> files, String extension) {
    List<File> filtered = new ArrayList<File>();
    for (File f: files) {
      if (f.toString().endsWith(extension)) {
        filtered.add(f);
      }
    }
    return filtered;
  }

  static private String[] sourceExtensions = {".scala", ".java"};
  static private List<File> extractSourceJars(CompileOptions opts, Path tmpParent) throws IOException {
    List<File> sourceFiles = new ArrayList<File>();

    for(String jarPath : opts.sourceJars) {
      if (jarPath.length() > 0){
        Path tmpPath = Files.createTempDirectory(tmpParent, "tmp");
        sourceFiles.addAll(extractJar(jarPath, tmpPath.toString(), sourceExtensions));
      }
    }

    return sourceFiles;
  }

  private static List<File> extractJar(String jarPath,
      String outputFolder,
      String[] extensions) throws IOException, FileNotFoundException {

    List<File> outputPaths = new ArrayList<File>();
    JarFile jar = new JarFile(jarPath);
    Enumeration<JarEntry> e = jar.entries();
    while (e.hasMoreElements()) {
      JarEntry file = e.nextElement();
      String thisFileName = file.getName();
      // we don't bother to extract non-scala/java sources (skip manifest)
      if (extensions != null && !matchesFileExtensions(thisFileName, extensions)) continue;
      File f = new File(outputFolder + File.separator + file.getName());

      if (file.isDirectory()) { // if its a directory, create it
        f.mkdirs();
        continue;
      }

      File parent = f.getParentFile();
      parent.mkdirs();
      outputPaths.add(f);

      InputStream is = jar.getInputStream(file); // get the input stream
      FileOutputStream fos = new FileOutputStream(f);
      while (is.available() > 0) {  // write contents of 'is' to 'fos'
        fos.write(is.read());
      }
      fos.close();
      is.close();
    }
    return outputPaths;
  }

  private static boolean matchesFileExtensions(String fileName, String[] extensions) {
    for (String e: extensions) {
      if (fileName.endsWith(e)) {
        return true;
      }
    }
    return false;
  }

  private static String[] encodeBazelTargets(String[] targets) {
    return Arrays.stream(targets)
            .map(ScalacProcessor::encodeBazelTarget)
            .toArray(String[]::new);
  }

  private static String encodeBazelTarget(String target) {
    return target.replace(":", ";");
  }

  private static boolean isModeEnabled(String mode) {
    return !"off".equals(mode);
  }

  private static String[] getPluginParamsFrom(CompileOptions ops) {
    String[] pluginParams;

    if (isModeEnabled(ops.dependencyAnalyzerMode)) {
      String[] targets = encodeBazelTargets(ops.indirectTargets);
      String currentTarget = encodeBazelTarget(ops.currentTarget);

      String[] pluginParamsInUse = {
              "-P:dependency-analyzer:direct-jars:" + String.join(":", ops.directJars),
              "-P:dependency-analyzer:indirect-jars:" + String.join(":", ops.indirectJars),
              "-P:dependency-analyzer:indirect-targets:" + String.join(":", targets),
              "-P:dependency-analyzer:mode:" + ops.dependencyAnalyzerMode,
              "-P:dependency-analyzer:current-target:" + currentTarget,
      };
      pluginParams = pluginParamsInUse;
    } else {
      pluginParams = new String[0];
    }
    return pluginParams;
  }

  private static void compileScalaSources(CompileOptions ops, String[] scalaSources, Path tmpPath) throws IllegalAccessException {

    String[] pluginParams = getPluginParamsFrom(ops);

    String[] constParams = {
      "-classpath",
      ops.classpath,
      "-d",
      tmpPath.toString()
    };

    String[] compilerArgs = GenericWorker.merge(
      ops.scalaOpts,
      ops.pluginArgs,
      constParams,
      pluginParams,
      scalaSources);

    MainClass comp = new MainClass();
    long start = System.currentTimeMillis();
    try {
      comp.process(compilerArgs);
    } catch (Throwable ex) {
      if (ex.toString().contains("scala.reflect.internal.Types$TypeError")) {
        throw new RuntimeException("Build failure with type error", ex);
      } else {
        throw ex;
      }
    }
    long stop = System.currentTimeMillis();
    if (ops.printCompileTime) {
      System.err.println("Compiler runtime: " + (stop - start) + "ms.");
    }

    try {
      Files.write(Paths.get(ops.statsfile), Arrays.asList(
        "build_time=" + Long.toString(stop - start)));
    } catch (IOException ex) {
        throw new RuntimeException(
            "Unable to write statsfile to " + ops.statsfile, ex);
    }

    ConsoleReporter reporter = (ConsoleReporter) reporterField.get(comp);

    if (reporter.hasErrors()) {
      reporter.printSummary();
      reporter.flush();
      throw new RuntimeException("Build failed");
    }
  }

  private static void removeTmp(Path tmp) throws IOException {
    if (tmp != null) {
      Files.walkFileTree(tmp, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
             Files.delete(file);
             return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
             Files.delete(dir);
             return FileVisitResult.CONTINUE;
         }
      });
    }
  }

  private static void copyResources(
      Map<String, Resource> resources,
      String resourceStripPrefix,
      Path dest) throws IOException {
    for(Entry<String, Resource> e : resources.entrySet()) {
      Path source = Paths.get(e.getKey());
      Resource resource = e.getValue();
      Path shortPath = Paths.get(resource.shortPath);
      String dstr;
      // Check if we need to modify resource destination path
      if (!"".equals(resourceStripPrefix)) {
  /**
   * NOTE: We are not using the Resource Hash Value as the destination path
   * when `resource_strip_prefix` present. The path in the hash value is computed
   * by the `_adjust_resources_path` in `scala.bzl`. These are the default paths,
   * ie, path that are automatically computed when there is no `resource_strip_prefix`
   * present. But when `resource_strip_prefix` is present, we need to strip the prefix
   * from the Source Path and use that as the new destination path
   * Refer Bazel -> BazelJavaRuleClasses.java#L227 for details
   */
        dstr = getResourcePath(shortPath, resourceStripPrefix);
      } else {
        dstr = resource.destination;
      }
      if (dstr.charAt(0) == '/') {
        // we don't want to copy to an absolute destination
        dstr = dstr.substring(1);
      }
      if (dstr.startsWith("../")) {
        // paths to external repositories, for some reason, start with a leading ../
        // we don't want to copy the resource out of our temporary directory, so
        // instead we replace ../ with external/
        // since "external" is a bit of reserved directory in bazel for these kinds
        // of purposes, we don't expect a collision in the paths.
        dstr = "external" + dstr.substring(2);
      }
      Path target = dest.resolve(dstr);
      File tfile = target.getParent().toFile();
      tfile.mkdirs();
      Files.copy(source, target);
    }
  }

  private static void copyClasspathResourcesToRoot(
    String[] classpathResourceFiles,
    Path dest
  ) throws IOException {
    for(String s : classpathResourceFiles) {
      Path source = Paths.get(s);
      Path target = dest.resolve(source.getFileName());

      if(Files.exists(target)) {
        System.err.println(
          "Classpath resource file " + source.getFileName()
          + " has a namespace conflict with another file: " + target.getFileName()
        );
      } else {
        Files.copy(source, target);
      }
    }
  }

  private static String getResourcePath(
      Path source,
      String resourceStripPrefix) throws RuntimeException {
    String sourcePath = source.toString();
    // check if the Resource file is under the specified prefix to strip
    if (!sourcePath.startsWith(resourceStripPrefix)) {
      // Resource File is not under the specified prefix to strip
      throw new RuntimeException("Resource File "
        + sourcePath
        + " is not under the specified strip prefix "
        + resourceStripPrefix);
    }
    String newResPath = sourcePath.substring(resourceStripPrefix.length());
    return newResPath;
  }
  private static void copyResourceJars(String[] resourceJars, Path dest) throws IOException {
    for (String jarPath: resourceJars) {
      extractJar(jarPath, dest.toString(), null);
    }
  }
}
