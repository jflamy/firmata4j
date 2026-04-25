/*
 * Raw serial dump test. Opens the port, optionally sends Firmata REPORT_FIRMWARE
 * (0xF0 0x79 0xF7), and prints any bytes received over a configurable window.
 *
 * Hardware-dependent. Skipped automatically when no serial port is found.
 *
 * Override:
 *     -Dfirmata.port=COM6
 *     -Dfirmata.dumpMs=8000          window length
 *     -Dfirmata.send=true|false      whether to send REPORT_FIRMWARE (default true)
 *     -Dfirmata.baud=57600
 */
package org.firmata4j.transport;

import static org.junit.Assume.assumeTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

public class RawSerialDumpTest {

    private static final Logger LOG = LoggerFactory.getLogger(RawSerialDumpTest.class);

    @Test
    public void dumpRawBytes() throws Exception {
        String portName = System.getProperty("firmata.port", autoDetect());
        assumeTrue("No serial port found and -Dfirmata.port not set; skipping.", portName != null);

        long dumpMs = Long.parseLong(System.getProperty("firmata.dumpMs", "8000"));
        boolean sendQuery = Boolean.parseBoolean(System.getProperty("firmata.send", "true"));
        int baud = Integer.parseInt(System.getProperty("firmata.baud", "57600"));

        SerialPort sp = SerialPort.getCommPort(portName);
        LOG.info("Opening {} @ {} baud (window {} ms, sendQuery={})", portName, baud, dumpMs, sendQuery);

        if (!sp.openPort(2000)) {
            throw new AssertionError("openPort returned false for " + portName
                    + " (errno=" + sp.getLastErrorCode() + ", loc=" + sp.getLastErrorLocation() + ")");
        }
        try {
            sp.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            sp.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

            // Force an Arduino auto-reset by pulsing DTR. On jSerialComm setDTR()
            // asserts the line (Arduino DTR pin LOW => RESET); clearDTR() releases it
            // and the bootloader runs. jSerialComm 2.11 no longer asserts DTR on open
            // for some USB-serial chips, so we do it explicitly.
            boolean pulseDtr = Boolean.parseBoolean(System.getProperty("firmata.pulseDtr", "true"));
            if (pulseDtr) {
                LOG.info("Pulsing DTR (assert -> release) to trigger Arduino auto-reset");
                sp.setDTR();
                sp.setRTS();
                Thread.sleep(100);
                sp.clearDTR();
                sp.clearRTS();
                // Wait for bootloader to time out and sketch to start.
                Thread.sleep(2000);
            }
            LOG.info("Modem lines: DSR={} CTS={} DCD={} RI={}",
                    sp.getDSR(), sp.getCTS(), sp.getDCD(), sp.getRI());

            AtomicInteger total = new AtomicInteger();
            sp.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    byte[] data = event.getReceivedData();
                    total.addAndGet(data.length);
                    LOG.info("RX {} bytes: {}", data.length, hex(data));
                }
            });

            if (sendQuery) {
                // F0 79 F7 = SysEx REPORT_FIRMWARE query
                byte[] req = new byte[] { (byte) 0xF0, (byte) 0x79, (byte) 0xF7 };
                LOG.info("TX {} bytes: {}", req.length, hex(req));
                sp.writeBytes(req, req.length);
            }

            long deadline = System.currentTimeMillis() + dumpMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            LOG.info("Total bytes received from {} during {} ms: {}", portName, dumpMs, total.get());
        } finally {
            sp.removeDataListener();
            sp.closePort();
        }
    }

    private static String autoDetect() {
        for (SerialPort sp : SerialPort.getCommPorts()) {
            String desc = String.valueOf(sp.getDescriptivePortName()).toLowerCase();
            if (desc.contains("bluetooth") || desc.contains("modem")) continue;
            return sp.getSystemPortName();
        }
        return null;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
