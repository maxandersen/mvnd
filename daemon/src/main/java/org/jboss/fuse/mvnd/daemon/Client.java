/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.fuse.mvnd.daemon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.maven.cli.CLIReportingUtils;
import org.jboss.fuse.mvnd.daemon.Message.BuildEvent;
import org.jboss.fuse.mvnd.daemon.Message.BuildException;
import org.jboss.fuse.mvnd.daemon.Message.BuildMessage;
import org.jboss.fuse.mvnd.daemon.Message.MessageSerializer;
import org.jboss.fuse.mvnd.jpm.Process;
import org.jboss.fuse.mvnd.jpm.ProcessImpl;
import org.jboss.fuse.mvnd.jpm.ScriptUtils;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

public class Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);
    public static final String DAEMON_DEBUG = "daemon.debug";

    public static void main(String[] argv) throws Exception {
        LOGGER.debug("Starting client");
        List<String> args = new ArrayList<>(Arrays.asList(argv));

        // Print version if needed
        boolean version = args.remove("-v") || args.remove("-version") || args.remove("--version");
        boolean showVersion = args.contains("-V") || args.contains("--show-version");
        boolean debug = args.contains("-X") || args.contains("--debug");
        if (version || showVersion || debug) {
            Properties props = new Properties();
            try (InputStream is = Client.class.getResourceAsStream("build.properties")) {
                props.load(is);
            }
            String v = buffer().strong( "Maven Daemon " + props.getProperty("version") ).toString()
                    + System.getProperty( "line.separator" )
                    + CLIReportingUtils.showVersion();
            System.out.println(v);
            if (version) {
                return;
            }
        }

        Path javaHome = Layout.javaHome();
        DaemonRegistry registry = DaemonRegistry.getDefault();

        boolean status = args.remove("--status");
        if (status) {
            System.out.println(String.format("    %36s  %5s  %5s  %7s  %s",
                    "UUID", "PID", "Port", "Status", "Timestamp"));
            registry.getAll().forEach(d ->
                    System.out.println(String.format("    %36s  %5s  %5s  %7s  %s",
                            d.getUid(), d.getPid(), d.getAddress(), d.getState(),
                            new Date(Math.max(d.getLastIdle(), d.getLastBusy())).toString())));
            return;
        }
        boolean stop = args.remove("--stop");
        if (stop) {
            DaemonInfo[] dis = registry.getAll().toArray(new DaemonInfo[0]);
            if (dis.length > 0) {
                System.out.println("Stopping " + dis.length + " running daemons");
                for (DaemonInfo di : dis) {
                    try {
                        new ProcessImpl(di.getPid()).destroy();
                    } catch (IOException t) {
                        System.out.println("Daemon " + di.getUid() + ": " + t.getMessage());
                    } catch (Exception t) {
                        System.out.println("Daemon " + di.getUid() + ": " + t);
                    } finally {
                        registry.remove(di.getUid());
                    }
                }
            }
            return;
        }

        String logFile = null;
        for (int i = 0; i < args.size() - 2; i++) {
            String arg = args.get(i);
            if ("-l".equals(arg) || "--log-file".equals(arg)) {
                logFile = args.get(i + 1);
                args.remove(i);
                args.remove(i);
                break;
            }
        }

        setDefaultArgs(args);

        DaemonConnector connector = new DaemonConnector(registry, Client::startDaemon, new MessageSerializer());
        List<String> opts = new ArrayList<>();
        DaemonClientConnection daemon = connector.connect(new DaemonCompatibilitySpec(javaHome.toString(), opts));

        daemon.dispatch(new Message.BuildRequest(
                args,
                Layout.getProperty("user.dir"),
                Layout.getProperty("maven.multiModuleProjectDirectory")));

        List<String> log = new ArrayList<>();
        LinkedHashMap<String, String> projects = new LinkedHashMap<>();
        Terminal terminal = TerminalBuilder.terminal();
        Display display = new Display(terminal, false);
        boolean exit = false;
        BuildException error = null;
        long lastUpdate = 0;
        while (!exit) {
            Message m = daemon.receive();
            if (m instanceof BuildException) {
                error = (BuildException) m;
                exit = true;
            } else if (m instanceof BuildEvent) {
                BuildEvent be = (BuildEvent) m;
                switch (be.getType()) {
                    case BuildStarted:
                        break;
                    case BuildStopped:
                        exit = true;
                        break;
                    case ProjectStarted:
                    case MojoStarted:
                    case MojoStopped:
                        projects.put(be.projectId, be.display);
                        break;
                    case ProjectStopped:
                        projects.remove(be.projectId);
                }
                // no need to refresh the display at every single step
                long curTime = System.currentTimeMillis();
                if (curTime - lastUpdate >= 10) {
                    Size size = terminal.getSize();
                    display.resize(size.getRows(), size.getColumns());
                    List<AttributedString> lines = new ArrayList<>();
                    projects.values().stream()
                            .map(AttributedString::fromAnsi)
                            .map(s -> s.columnSubSequence(0, size.getColumns() - 1))
                            .forEachOrdered(lines::add);
                    // Make sure we don't try to display more lines than the terminal height
                    int rem = 0;
                    while (lines.size() >= terminal.getHeight()) {
                        lines.remove(0);
                        rem++;
                    }
                    lines.add(0, new AttributedString("Building..." + (rem > 0 ? " (" + rem + " more)" : "")));
                    display.update(lines, -1);
                    lastUpdate = curTime;
                }
            } else if (m instanceof BuildMessage) {
                BuildMessage bm = (BuildMessage) m;
                log.add(bm.getMessage());
            }
        }
        display.update(Collections.emptyList(), 0);
        if (error != null) {
            AttributedStyle s = new AttributedStyle().bold().foreground(AttributedStyle.RED);
            String msg;
            if (UnrecognizedOptionException.class.getName().equals(error.getClassName())) {
                msg = "Unable to parse command line options: " + error.getMessage();
            } else {
                msg = error.getClassName() + ": " + error.getMessage();
            }
            terminal.writer().println(new AttributedString(msg, s).toAnsi());
        }
        terminal.flush();

        LOGGER.debug("Done receiving, printing log");

        if (logFile != null) {
            try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(logFile))) {
                for (String l : log) {
                    bw.write(l);
                    bw.newLine();
                }
            }
        } else {
            log.forEach(terminal.writer()::println);
            terminal.flush();
        }

        LOGGER.debug("Done !");
    }

    static void setDefaultArgs(List<String> args) {
        if (!args.stream().anyMatch(arg -> arg.startsWith("-T") || arg.equals("--threads"))) {
            args.add("-T1C");
        }
        if (!args.stream().anyMatch(arg -> arg.startsWith("-b") || arg.equals("--builder"))) {
            args.add("-bsmart");
        }
    }

    public static String startDaemon() {
//        DaemonParameters parms = new DaemonParameters();
//        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
//
//        }
//            List<String> args = new ArrayList<>();
//            args.add(javaHome.resolve(java).toString());
//            args.addAll(parms.getEffectiveJvmArgs());
//            args.add("-cp");
//            args.add(classpath);

        String uid = UUID.randomUUID().toString();
        Path mavenHome = Layout.mavenHome();
        Path javaHome = Layout.javaHome();
        Path workingDir = Layout.userDir();
        String command = "";
        try {
            String url = ServerMain.class.getClassLoader().getResource(Server.class.getName().replace('.', '/') + ".class").toString();
            String classpath = url.substring("file:jar:".length(), url.indexOf('!'));
            String java = ScriptUtils.isWindows() ? "bin\\java.exe" : "bin/java";
            List<String> args = new ArrayList<>();
            args.add("\"" + javaHome.resolve(java) + "\"");
            args.add("-classpath");
            args.add("\"" + classpath + "\"");
            if (Boolean.getBoolean(DAEMON_DEBUG)) {
                args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000");
            }
            args.add("-Dmaven.home=\"" + mavenHome + "\"");
            args.add("-Dlogback.configurationFile=logback.xml");
            args.add("-Ddaemon.uid=" + uid);
            args.add("-Xmx4g");
            if (System.getProperty(Server.DAEMON_IDLE_TIMEOUT) != null) {
                args.add("-D" + Server.DAEMON_IDLE_TIMEOUT + "=" + System.getProperty(Server.DAEMON_IDLE_TIMEOUT));
            }

            args.add(ServerMain.class.getName());
            command = String.join(" ", args);

            LOGGER.debug("Starting daemon process: uid = {}, workingDir = {}, daemonArgs: {}", uid, workingDir, command);
            Process.create(workingDir.toFile(), command);
            return uid;
        } catch (Exception e) {
            throw new DaemonException.StartException(
                    String.format("Error starting daemon: uid = %s, workingDir = %s, daemonArgs: %s",
                            uid, workingDir, command), e);
        }
    }

}
