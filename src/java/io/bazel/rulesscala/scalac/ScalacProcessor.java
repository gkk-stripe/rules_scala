package io.bazel.rulesscala.scalac;

import io.bazel.rulesscala.jar.JarCreator;
import io.bazel.rulesscala.worker.GenericWorker;
import io.bazel.rulesscala.worker.Processor;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
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
          String sha1 = bytesToHex(localMd.digest());
          System.out.println("[digest] " + file + ": " + sha1);
//          try (Stream<String> lines = Files.lines(file.toPath())) {
//              System.out.println("### The file contents");
//              lines.forEach(System.out::println);
//          }
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

  private void printTimeSinceStartup(long startTime, String label) {
      long milisSinceStart = System.currentTimeMillis() - startTime;
      System.out.println("[" + milisSinceStart + "]: " + label);
  }

  @Override
  public void processRequest(List<String> args) throws Exception {
    Path tmpPath = null;
    try {
        long startTime = System.currentTimeMillis();
        printTimeSinceStartup(startTime,"Before CompileOptions creation");
      CompileOptions ops = new CompileOptions(args);

      MessageDigest md = MessageDigest.getInstance("SHA");

      Path outputPath = FileSystems.getDefault().getPath(ops.outputName);
        printTimeSinceStartup(startTime,"Before createTempDirectory");
      tmpPath = Files.createTempDirectory(outputPath.getParent(), "tmp");

      // compute the digest
        {

            Stream<String> paths = Stream.of(
                    ops.files,
                    ops.sourceJars,
                    ops.javaFiles,
                    ops.classpath.split(":"),
                    ops.resourceFiles.keySet().stream().sorted().toArray(String[]::new),
                    ops.classpathResourceFiles,
                    new String[]{ops.manifestPath}

            )
                    .map(Stream::of)
                    .reduce(Stream::concat).orElse(Stream.empty());
            List<File> inputPaths = paths
                            .map(File::new).collect(Collectors.toList());
            inputPaths.forEach(System.out::println);
            digestFileCollections(md, inputPaths);

        }

        Path cacheDir = Paths.get("/Users/gkk/tmp/scalac-worker-cache");
        byte[] digestBytes = md.digest();
        String sha1 = bytesToHex(digestBytes);
        String cacheKey = sha1;
        System.out.println(cacheKey);
        File cacheKeyOnDisk = Paths.get(cacheDir.toString(), cacheKey).toFile();
        File cacheKeyOnDiskFinished = Paths.get(cacheDir.toString(), cacheKey, "task_finished").toFile();

//        File cacheTmpDirOnDisk = Paths.get(cacheKeyOnDisk.toString(), "scalac_output").toFile();
        Path cacheStatsFileOnDisk = Paths.get(cacheKeyOnDisk.toString(), "stats_file");

        /**
         * Compile scala sources if available (if there are none, we will simply
         * compile java sources).
         */
        if (cacheKeyOnDisk.exists()) {
            System.out.println("The " + cacheKeyOnDisk + " exists");
//            while (!cacheKeyOnDiskFinished.exists()) {
//                System.out.println("Waiting for " + cacheKeyOnDiskFinished);
//                Thread.sleep(1000);
//            }
            Files.copy(Paths.get(cacheKeyOnDisk.toString(), "output_jar"), outputPath);
            if (ops.iJarEnabled) {
                Files.copy(Paths.get(cacheKeyOnDisk.toString(), "output_ijar"), Paths.get(ops.ijarOutput));
            }
            Files.copy(cacheStatsFileOnDisk, Paths.get(ops.statsfile));
            return;
        }

        cacheKeyOnDisk.mkdir();
        printTimeSinceStartup(startTime,"Before extractSourceJars");
        List<File> jarFiles = extractSourceJars(ops, outputPath.getParent());
        System.out.println("Printing extracted jar files");
        jarFiles.forEach(System.out::println);
        printTimeSinceStartup(startTime,"Before filterFilesByExtension(.scala)");
        List<File> scalaJarFiles = filterFilesByExtension(jarFiles, ".scala");
        printTimeSinceStartup(startTime,"Before filterFilesByExtension(.java)");
        List<File> javaJarFiles = filterFilesByExtension(jarFiles, ".java");

        String[] scalaSources = collectSrcJarSources(ops.files, scalaJarFiles, javaJarFiles);

        printTimeSinceStartup(startTime,"Before scala sources hash");
        printTimeSinceStartup(startTime,"After scala sources hash");

        String[] javaSources = GenericWorker.appendToString(ops.javaFiles, javaJarFiles);

        if (scalaSources.length == 0 && javaSources.length == 0) {
            throw new RuntimeException("Must have input files from either source jars or local files.");
        }

        if (scalaSources.length > 0) {
            System.out.println("Compiling scalaSources");
            compileScalaSources(ops, scalaSources, tmpPath);
            Files.copy(Paths.get(ops.statsfile), cacheStatsFileOnDisk);
        }

      System.out.println("After compile/restore from the cache");

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
      Files.copy(outputPath, Paths.get(cacheKeyOnDisk.toString(), "output_jar"));
        {
            Path infoPath = Paths.get(cacheKeyOnDisk.toString(), "info" + System.currentTimeMillis());
            Files.write(infoPath, Arrays.asList(ops.files));
            Files.write(infoPath, Arrays.asList(ops.javaFiles), StandardOpenOption.APPEND);
            Files.write(infoPath, Arrays.asList(ops.sourceJars), StandardOpenOption.APPEND);
        }

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
        Files.copy(Paths.get(ops.ijarOutput), Paths.get(cacheKeyOnDisk.toString(), "output_ijar"));
      }
      boolean createdMarkerFile = cacheKeyOnDiskFinished.createNewFile();
      System.out.println(cacheKeyOnDiskFinished.toString() + " created = " + createdMarkerFile);
      assert createdMarkerFile: cacheKeyOnDiskFinished.toString();
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
                    System.out.println("Copy " + sourcePath + " to " + " " + target);
                    Files.copy(
                            /*Source Path*/
                            sourcePath,
                            /*Destination Path */
                            // TODO: replace is required because we were getting random FileAlreadyExists errors
                            // could it be that following symlinks we end up copying the same file multiple times?
                            target, StandardCopyOption.REPLACE_EXISTING);
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

      InputStream is = new BufferedInputStream(jar.getInputStream(file)); // get the input stream
      OutputStream fos = new BufferedOutputStream(new FileOutputStream(f));
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
