package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.runtime.NetworkAddress.Nic;
import org.peekaboot.backend.domain.runtime.NetworkAddress.Signals;

class NetworkAddressTest {

    private static final Duration TEST_BUDGET = Duration.ofSeconds(2);

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    private static Signals signals(List<Nic> nics) {
        return new Signals(() -> nics, address -> "host-for-" + address.getHostAddress(), TEST_BUDGET);
    }

    @Test
    void collectsAddressesFromEveryUpInterfaceInOrder() {
        List<Nic> nics = List.of(
                new Nic(true, List.of(ip("10.1.2.3"))), new Nic(true, List.of(ip("192.168.0.7"), ip("2001:db8::7"))));

        assertThat(NetworkAddress.discover(signals(nics)))
                .containsExactly(
                        new NetworkAddress("10.1.2.3", "host-for-10.1.2.3"),
                        new NetworkAddress("192.168.0.7", "host-for-192.168.0.7"),
                        new NetworkAddress(
                                ip("2001:db8::7").getHostAddress(),
                                "host-for-" + ip("2001:db8::7").getHostAddress()));
    }

    @Test
    void skipsLoopbackAndLinkLocalAddresses() {
        List<Nic> nics = List.of(
                new Nic(true, List.of(ip("127.0.0.1"), ip("::1"), ip("169.254.10.10"), ip("fe80::1"), ip("10.0.0.5"))));

        assertThat(NetworkAddress.discover(signals(nics)))
                .containsExactly(new NetworkAddress("10.0.0.5", "host-for-10.0.0.5"));
    }

    @Test
    void skipsInterfacesThatAreDown() {
        List<Nic> nics = List.of(new Nic(false, List.of(ip("10.1.2.3"))), new Nic(true, List.of(ip("192.168.0.7"))));

        assertThat(NetworkAddress.discover(signals(nics)))
                .containsExactly(new NetworkAddress("192.168.0.7", "host-for-192.168.0.7"));
    }

    @Test
    void aLookupReturningTheLiteralAddressCountsAsNoHostname() {
        Signals signals = new Signals(
                () -> List.of(new Nic(true, List.of(ip("10.1.2.3")))), InetAddress::getHostAddress, TEST_BUDGET);

        assertThat(NetworkAddress.discover(signals)).containsExactly(new NetworkAddress("10.1.2.3", null));
    }

    @Test
    void aLookupMissingTheBudgetRendersWithoutAHostname() {
        Signals signals = new Signals(
                () -> List.of(new Nic(true, List.of(ip("10.1.2.3"), ip("192.168.0.7")))),
                address -> {
                    if (address.getHostAddress().equals("10.1.2.3")) {
                        return "fast-host";
                    }
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "too-late";
                },
                Duration.ofMillis(200));

        long start = System.nanoTime();
        List<NetworkAddress> addresses = NetworkAddress.discover(signals);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(addresses)
                .containsExactly(new NetworkAddress("10.1.2.3", "fast-host"), new NetworkAddress("192.168.0.7", null));
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aFailingInterfaceEnumerationYieldsAnEmptyList() {
        Signals signals = new Signals(
                () -> {
                    throw new SocketException("no interfaces for you");
                },
                InetAddress::getCanonicalHostName,
                TEST_BUDGET);

        assertThat(NetworkAddress.discover(signals)).isEmpty();
    }
}
