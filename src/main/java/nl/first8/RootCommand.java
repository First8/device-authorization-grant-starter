package nl.first8;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(name = "cli", mixinStandardHelpOptions = true, subcommands = { DeviceCodeCommand.class})
public class RootCommand implements Runnable {


    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
