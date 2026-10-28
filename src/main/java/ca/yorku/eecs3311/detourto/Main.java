package ca.yorku.eecs3311.detourto;

import ca.yorku.eecs3311.detourto.presentation.DetourCli;

/** Entry point for {@code java -jar target/detourto.jar ...}; the commands live in DetourCli. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(DetourCli.execute(args));
    }
}
