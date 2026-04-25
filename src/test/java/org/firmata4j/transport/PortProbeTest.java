/*
 * Probe a real serial port for Firmata firmware. Hardware-dependent test.
 *
 * Skipped automatically when no Arduino-like serial port is found.
 *
 * Override port selection or timeout via system properties:
 *     -Dfirmata.port=COM6
 *     -Dfirmata.probeTimeoutMs=15000
 */
package org.firmata4j.transport;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.firmata4j.firmata.FirmataDevice;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;

public class PortProbeTest {

    private static final Logger LOG = LoggerFactory.getLogger(PortProbeTest.class);

    @Test
    public void probePort() throws Exception {
        String portName = System.getProperty("firmata.port", autoDetectPort());
        assumeTrue("No serial port found and -Dfirmata.port not set; skipping.", portName != null);

        long timeoutMs = Long.parseLong(System.getProperty("firmata.probeTimeoutMs", "15000"));
        LOG.info("Probing {} (timeout {} ms)", portName, timeoutMs);

        FirmataDevice device = new FirmataDevice(new JSerialCommTransport(portName));
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Callable<String> task = () -> {
                long t0 = System.currentTimeMillis();
                device.start();
                device.ensureInitializationIsDone();
                String fw = device.getFirmware();
                LOG.info("Firmware on {}: '{}' ({} ms)", portName, fw, System.currentTimeMillis() - t0);
                return fw;
            };
            Future<String> future = exec.submit(task);
            try {
                String firmware = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                assertNotNull("No firmware reported by " + portName, firmware);
            } catch (TimeoutException te) {
                future.cancel(true);
                throw new AssertionError("Probe timed out on " + portName + " after " + timeoutMs + " ms", te);
            }
        } finally {
            try {
                device.stop();
            } catch (Exception ignore) {
                // best effort
            }
            exec.shutdownNow();
        }
    }

    private static String autoDetectPort() {
        for (SerialPort sp : SerialPort.getCommPorts()) {
            String name = sp.getSystemPortName();
            String desc = String.valueOf(sp.getDescriptivePortName()).toLowerCase();
            // Skip obvious non-Arduino ports (Bluetooth, modem, etc.)
            if (desc.contains("bluetooth") || desc.contains("modem")) {
                continue;
            }
            LOG.info("Auto-detected candidate port: {} ({})", name, desc);
            return name;
        }
        return null;
    }
}
