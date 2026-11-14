import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds the lock of the file its one argument names until its standard input ends. */
public final class HoldLock {

    private HoldLock() {}

    public static void main(String[] args) throws IOException {
        try (FileChannel channel =
                FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.lock();
            System.out.println("locked");
            System.out.flush();
            System.in.transferTo(OutputStream.nullOutputStream());
        }
    }
}
