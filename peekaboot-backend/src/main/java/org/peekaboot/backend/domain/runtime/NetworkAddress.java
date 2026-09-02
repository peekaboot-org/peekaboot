package org.peekaboot.backend.domain.runtime;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One non-local IP address of the machine and, best-effort, the hostname it reverse-resolves
 * to ({@code null} when the lookup fails, misses the resolution budget, or only echoes the
 * literal address back). Discovery walks the up network interfaces and skips loopback and
 * link-local addresses; a machine that lets a JVM see none of that simply has no addresses.
 */
public record NetworkAddress(String address, String hostname) {

    @FunctionalInterface
    interface NicSource {
        List<Nic> nics() throws SocketException;
    }

    @FunctionalInterface
    interface HostnameResolver {
        String resolve(InetAddress address);
    }

    /** One network interface, reduced to the two facts discovery filters on. */
    record Nic(boolean up, List<InetAddress> addresses) {}

    /**
     * The discovery inputs, read from the host once ({@code MachineInfo.current()} caches
     * the result: they are static for the process's lifetime). A record so tests can state
     * an interface layout and resolver behaviour outright instead of faking a network.
     */
    record Signals(NicSource nics, HostnameResolver resolver, Duration budget) {

        static Signals fromRuntime() {
            return new Signals(NetworkAddress::readNics, InetAddress::getCanonicalHostName, Duration.ofSeconds(1));
        }
    }

    static List<NetworkAddress> discover(Signals signals) {
        List<InetAddress> candidates = new ArrayList<>();
        try {
            for (Nic nic : signals.nics().nics()) {
                if (!nic.up()) {
                    continue;
                }
                for (InetAddress address : nic.addresses()) {
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        candidates.add(address);
                    }
                }
            }
        } catch (SocketException e) {
            // the machine hides its interfaces from us - there is simply nothing to show
            return List.of();
        }
        return candidates.isEmpty() ? List.of() : withHostnames(candidates, signals.resolver(), signals.budget());
    }

    /**
     * Reverse lookups have no JDK timeout and can block for seconds on broken DNS, so they
     * run in parallel on virtual threads against one shared deadline; an address whose
     * lookup misses it keeps a {@code null} hostname and the straggler thread is left
     * behind (which is also why the executor is shut down rather than close()d - close()
     * would await exactly the stall the budget exists to avoid).
     */
    private static List<NetworkAddress> withHostnames(
            List<InetAddress> addresses, HostnameResolver resolver, Duration budget) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<String>> lookups = new ArrayList<>();
            for (InetAddress address : addresses) {
                lookups.add(executor.submit(() -> resolver.resolve(address)));
            }
            long deadline = System.nanoTime() + budget.toNanos();
            List<NetworkAddress> result = new ArrayList<>(addresses.size());
            for (int i = 0; i < addresses.size(); i++) {
                InetAddress address = addresses.get(i);
                result.add(new NetworkAddress(
                        address.getHostAddress(), hostnameOrNull(lookups.get(i), address, deadline)));
            }
            return List.copyOf(result);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String hostnameOrNull(Future<String> lookup, InetAddress address, long deadline) {
        try {
            String hostname = lookup.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return hostname == null || hostname.equals(address.getHostAddress()) ? null : hostname;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<Nic> readNics() throws SocketException {
        List<Nic> nics = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            nics.add(new Nic(nic.isUp(), Collections.list(nic.getInetAddresses())));
        }
        return nics;
    }
}
