/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.core.launcher;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.wildfly.core.launcher.logger.LauncherMessages;

/**
 * Represents a JVM description.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class Jvm {
    private static final String JAVA_EXE;
    private static final Path JAVA_HOME;
    private static final boolean SUPPORTS_SECURITY_MANGER = Runtime.version().feature() < 24;
    private static final boolean ENHANCED_SECURITY_MANAGER = SUPPORTS_SECURITY_MANGER && Runtime.version()
            .feature() >= 12;

    static {
        String exe = "java";
        if (Environment.isWindows()) {
            exe = "java.exe";
        }
        JAVA_EXE = exe;
        final String javaHome = System.getProperty("java.home");
        JAVA_HOME = Paths.get(javaHome);
    }

    private static final Jvm DEFAULT = new Jvm(JAVA_HOME, SUPPORTS_SECURITY_MANGER, ENHANCED_SECURITY_MANAGER);

    private final Path path;
    private final boolean isSecurityManagerSupported;
    private final boolean enhancedSecurityManager;

    private Jvm(final Path path, final boolean isSecurityManagerSupported, final boolean enhancedSecurityManager) {
        this.path = path;
        this.isSecurityManagerSupported = isSecurityManagerSupported;
        this.enhancedSecurityManager = enhancedSecurityManager;
    }

    /**
     * The current JVM.
     *
     * @return the current JVM
     */
    static Jvm current() {
        return DEFAULT;
    }

    /**
     * Creates a new JVM. If the {@code javaHome} is {@code null} the {@linkplain #current() current} JVM is returned.
     *
     * @param javaHome the path to the Java home
     *
     * @return a JVM descriptor based on the Java home path
     */
    static Jvm of(final String javaHome) {
        if (javaHome == null) {
            return DEFAULT;
        }
        return of(Paths.get(javaHome));
    }

    /**
     * Creates a new JVM. If the {@code javaHome} is {@code null} the {@linkplain #current() current} JVM is returned.
     *
     * @param javaHome the path to the Java home
     *
     * @return a JVM descriptor based on the Java home path
     */
    static Jvm of(final Path javaHome) {
        if (javaHome == null || javaHome.equals(JAVA_HOME)) {
            return DEFAULT;
        }
        final Path path = validateJavaHome(javaHome);
        return new Jvm(path, isSecurityManagerSupported(javaHome), hasEnhancedSecurityManager(javaHome));
    }

    /**
     * The the command which can launch this JVM.
     *
     * @return the command
     */
    public String getCommand() {
        return resolveJavaCommand(path);
    }

    /**
     * The path to this JVM.
     *
     * @return the path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Indicates whether or not this is a modular JVM.
     *
     * @return {@code true} if this is a modular JVM, otherwise {@code false}
     */
    @Deprecated(forRemoval = true, since = "1.0")
    public boolean isModular() {
        return true;
    }

    /**
     * Indicates if the security manager is supported for this JVM.
     *
     * @return {@code true} if this is a security manager is supported in this JVM, otherwise {@code false}
     */
    public boolean isSecurityManagerSupported() {
        return isSecurityManagerSupported;
    }

    /**
     * Indicates whether or not this is a modular JVM supporting special SecurityManager values like "allow", "disallow" & "default"
     *
     * @return {@code true} if this is a modular JVM with enhanced SecurityManager, otherwise {@code false}
     */
    public boolean enhancedSecurityManagerAvailable() {
        return enhancedSecurityManager;
    }

    private static boolean isSecurityManagerSupported(final Path javaHome) {
        // Next check for a $JAVA_HOME/release file, for a JRE this will not exist
        final Path releaseFile = javaHome.resolve("release");
        if (Files.isReadable(releaseFile) && Files.isRegularFile(releaseFile)) {
            // Read the file and look for a JAVA_VERSION property
            try (final BufferedReader reader = Files.newBufferedReader(releaseFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        // Get the version value
                        final int index = line.indexOf('=');
                        return isSecurityManagerSupported(line.substring(index + 1).replace("\"", ""));
                    }
                }
            } catch (IOException ignore) {
            }
        }
        // Final check is to launch a new process with some modular JVM arguments and check the exit code
        return isSecurityManagerSupportedInJvm(javaHome);
    }

    private static boolean isSecurityManagerSupported(final String version) {
        if (version != null) {
            try {
                final String[] versionParts = version.split("\\.");
                if (versionParts.length >= 1) {
                    return Integer.parseInt(versionParts[0]) < 24;
                }
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    /**
     * Checks to see if the {@code javaHome} supports special security manager tokens like "allow", "disallow" & "default"
     *
     * @param javaHome the Java Home if {@code null} an attempt to discover the Java Home will be done
     *
     * @return {@code true} if this is a modular environment
     */
    private static boolean hasEnhancedSecurityManager(final Path javaHome) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaCommand(javaHome));
        cmd.add("-Djava.security.manager=allow");
        cmd.add("-version");
        return checkProcessStatus(cmd);
    }

    static boolean isPackageAvailable(final Path javaHome, final String optionalModularArgument) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaCommand(javaHome));
        cmd.add(optionalModularArgument);
        cmd.add("-version");
        return checkProcessStatus(cmd);
    }

    /**
     * Checks to see if the {@code javaHome} supports the security manager.
     *
     * @param javaHome the Java Home
     *
     * @return {@code true} if this JVM supports the security manager
     */
    private static boolean isSecurityManagerSupportedInJvm(final Path javaHome) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaCommand(javaHome));
        cmd.add("-Djava.security.manager");
        cmd.add("-version");
        return checkProcessStatus(cmd);
    }

    /**
     * Checks the process status.
     *
     * @param cmd command to execute
     *
     * @return {@code true} if command was successful, {@code false} if process failed.
     */
    private static boolean checkProcessStatus(final List<String> cmd) {
        boolean result;
        final ProcessBuilder builder = new ProcessBuilder(cmd);
        Process process = null;
        Path stdout = null;
        try {
            // Create a temporary file for stdout
            stdout = Files.createTempFile("stdout", ".txt");
            process = builder.redirectErrorStream(true)
                    .redirectOutput(stdout.toFile()).start();

            if (process.waitFor(30, TimeUnit.SECONDS)) {
                result = process.exitValue() == 0;
            } else {
                result = false;
            }
        } catch (IOException | InterruptedException e) {
            result = false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (stdout != null) {
                try {
                    if (containsWarning(stdout)) {
                        result = false;
                    }
                    Files.deleteIfExists(stdout);
                } catch (IOException ignore) {
                }
            }
        }
        return result;
    }

    private static boolean containsWarning(final Path logFile) throws IOException {
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile.toFile())))) {
            while ((line = br.readLine()) != null) {
                if (line.startsWith("WARNING:")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the Java executable command.
     *
     * @param javaHome the java home directory or {@code null} to use the default
     *
     * @return the java command to use
     */
    private static String resolveJavaCommand(final Path javaHome) {
        final String exe;
        if (javaHome == null) {
            exe = "java";
        } else {
            exe = javaHome.resolve("bin").resolve("java").toString();
        }
        if (exe.contains(" ")) {
            return "\"" + exe + "\"";
        }
        return exe;
    }

    private static Path validateJavaHome(final Path javaHome) {
        if (javaHome == null || Files.notExists(javaHome)) {
            throw LauncherMessages.MESSAGES.pathDoesNotExist(javaHome);
        }
        if (!Files.isDirectory(javaHome)) {
            throw LauncherMessages.MESSAGES.invalidDirectory(javaHome);
        }
        final Path result = javaHome.toAbsolutePath().normalize();
        final Path exe = result.resolve("bin").resolve(JAVA_EXE);
        if (Files.notExists(exe)) {
            final int count = exe.getNameCount();
            throw LauncherMessages.MESSAGES.invalidDirectory(exe.subpath(count - 2, count).toString(), javaHome);
        }
        return result;
    }
}
