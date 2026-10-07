package com.oak.dms.scanning.helper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A ZIP is accepted only when it's complete, readable, unencrypted and within what the scanner can check. */
class ZipArchiveCheckTest {

    private static final int MAX_ENTRIES = 10;
    private static final long MAX_UNPACKED = 1_000_000;

    /** A ZIP of name/content pairs, deflated. */
    static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * The layout OneDrive, SharePoint and Google Drive download: every entry stored (no compression)
     * with general-purpose flag bit 3 - its CRC and sizes come in a data descriptor after the data,
     * not in its local header.
     */
    static byte[] storedWithDescriptors(String... namesAndContents) {
        ByteBuffer out = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer central = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        int count = namesAndContents.length / 2;
        for (int i = 0; i < namesAndContents.length; i += 2) {
            byte[] name = namesAndContents[i].getBytes(StandardCharsets.UTF_8);
            byte[] data = namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = out.position();
            out.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0008).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(0).putInt(0)
                    .putShort((short) name.length).putShort((short) 0).put(name).put(data);
            out.putInt(0x08074b50).putInt((int) crc.getValue()).putInt(data.length).putInt(data.length);
            central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0x0008)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt((int) crc.getValue())
                    .putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(name);
        }
        int centralOffset = out.position();
        int centralSize = central.position();
        out.put(central.array(), 0, centralSize);
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) count)
                .putShort((short) count).putInt(centralSize).putInt(centralOffset).putShort((short) 0);
        return Arrays.copyOf(out.array(), out.position());
    }

    /** Offset of {@code signature} (little-endian int) in {@code zip}, from the end. */
    private static int lastIndexOf(byte[] zip, int signature) {
        for (int i = zip.length - 4; i >= 0; i--) {
            if (ByteBuffer.wrap(zip, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == signature) {
                return i;
            }
        }
        throw new AssertionError("signature not found");
    }

    private static ZipArchiveCheck.Result check(byte[] zip) {
        return ZipArchiveCheck.check(new ByteArrayInputStream(zip), MAX_ENTRIES, MAX_UNPACKED);
    }

    /** A ZIP of name/bytes pairs, deflated - for binary entries. */
    private static byte[] zipOf(Object... namesAndBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBytes.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) namesAndBytes[i]));
                zip.write((byte[]) namesAndBytes[i + 1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** The start of a Windows program: "MZ", and at 0x3C the offset of its PE header. */
    private static byte[] windowsProgram() {
        byte[] exe = new byte[256];
        exe[0] = 'M';
        exe[1] = 'Z';
        exe[0x3c] = (byte) 0x80;
        exe[0x80] = 'P';
        exe[0x81] = 'E';
        return exe;
    }

    @Test
    void anInstallerInside_isRefusedByName() throws IOException {
        ZipArchiveCheck.Result result = check(zip("readme.txt", "hi", "tools/Setup.MSI", "not really an msi"));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("program or script").contains("tools/Setup.MSI");
    }

    @Test
    void scriptsInside_areRefusedByName() throws IOException {
        assertThat(check(zip("run.ps1", "Write-Host hi")).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(check(zip("a/b/start.BAT", "@echo off")).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }

    @Test
    void aProgramRenamedAsADocument_isRefusedByContent() throws IOException {
        ZipArchiveCheck.Result result = check(zipOf("invoice.pdf", windowsProgram()));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("invoice.pdf");
    }

    @Test
    void aShellScriptWithoutAnExtension_isRefusedByContent() throws IOException {
        assertThat(check(zip("install", "#!/bin/sh\nrm -rf /")).outcome())
                .isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }

    @Test
    void textThatMerelyStartsWithMZ_isAccepted() throws IOException {
        String text = "MZ Holdings quarterly figures - " + "revenue up, costs down. ".repeat(10);

        assertThat(check(zip("notes.txt", text)).outcome()).isEqualTo(ZipArchiveCheck.Outcome.VALID);
    }

    @Test
    void aProgramInANestedZip_isRefused() throws IOException {
        byte[] inner = zip("docs/a.txt", "fine", "setup.msi", "installer");
        byte[] innerTwice = zipOf("inner.zip", inner);

        ZipArchiveCheck.Result result = check(zipOf("report.txt", "ok".getBytes(StandardCharsets.UTF_8),
                "bundle.zip", innerTwice));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("bundle.zip > inner.zip > setup.msi");
    }

    @Test
    void officeDocumentsInside_areNotTreatedAsNestedArchives() throws IOException {
        // A .docx is itself a ZIP; what's inside one isn't checked against the list.
        byte[] docx = zip("[Content_Types].xml", "<Types/>", "word/document.xml", "<w:document/>");

        assertThat(check(zipOf("contract.docx", docx)).outcome()).isEqualTo(ZipArchiveCheck.Outcome.VALID);
    }

    @Test
    void aBlocklistFromConfig_isUsed() throws IOException {
        byte[] zip = zip("data.csv", "a,b", "notes.txt", "hi");

        assertThat(ZipArchiveCheck.check(new ByteArrayInputStream(zip), MAX_ENTRIES, MAX_UNPACKED,
                java.util.Set.of("csv")).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }

    @Test
    void aGoodZip_isValid() throws IOException {
        ZipArchiveCheck.Result result = check(zip("a.txt", "alpha", "docs/b.txt", "beta".repeat(100)));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.VALID);
        assertThat(result.entries()).isEqualTo(2);
        assertThat(result.uncompressedBytes()).isEqualTo(5 + 400);
    }

    @Test
    void aTruncatedZip_isRefusedAsDamaged() throws IOException {
        byte[] whole = zip("a.txt", "alpha".repeat(500), "b.txt", "beta".repeat(500));

        for (int cut : new int[]{whole.length / 2, whole.length - 10, 40}) {
            ZipArchiveCheck.Result result = check(Arrays.copyOf(whole, cut));
            assertThat(result.outcome()).as("cut at " + cut).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
            assertThat(result.message()).contains("damaged or incomplete");
        }
    }

    @Test
    void corruptedData_failsItsCrc() throws IOException {
        byte[] zip = zip("a.txt", "the same line again and again\n".repeat(200));
        zip[50] ^= 0x5A; // inside the first entry's deflated data

        assertThat(check(zip).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }

    @Test
    void anEmptyZip_isRefused() throws IOException {
        ZipArchiveCheck.Result result = check(zip());

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("empty");
    }

    @Test
    void pastTheScannersLimits_isRefused() throws IOException {
        String[] many = new String[(MAX_ENTRIES + 1) * 2];
        for (int i = 0; i < MAX_ENTRIES + 1; i++) {
            many[2 * i] = "f" + i + ".txt";
            many[2 * i + 1] = "x";
        }
        ZipArchiveCheck.Result tooMany = check(zip(many));
        assertThat(tooMany.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(tooMany.message()).contains("more than " + MAX_ENTRIES + " files");

        // A small archive that unpacks huge - a "zip bomb" - is stopped as soon as it passes the limit.
        ZipArchiveCheck.Result bomb = check(zip("zeros.bin", "\0".repeat((int) MAX_UNPACKED + 1)));
        assertThat(bomb.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(bomb.message()).contains("Unpacked");
    }

    @Test
    void aPasswordProtectedEntry_isReportedAsEncrypted() throws IOException {
        // A stored entry with general-purpose flag bit 0 (encrypted) set in its local header.
        byte[] content = "secret".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("secret.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        byte[] zip = out.toByteArray();
        zip[6] |= 1; // local header
        zip[lastIndexOf(zip, 0x02014b50) + 8] |= 1; // and central directory, as real encrypted ZIPs have

        assertThat(check(zip).outcome()).isEqualTo(ZipArchiveCheck.Outcome.ENCRYPTED);
    }

    @Test
    void aOneDriveStyleZip_storedWithTrailingDescriptors_isValid() {
        ZipArchiveCheck.Result result = check(storedWithDescriptors(
                "Reports/q1.pdf", "%PDF-1.4 quarter one", "Reports/notes.txt", "notes ".repeat(50)));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.VALID);
        assertThat(result.entries()).isEqualTo(2);
        assertThat(result.uncompressedBytes()).isEqualTo(20 + 300);
    }

    @Test
    void aStoredEntryWhoseBytesChanged_failsItsCrc() {
        byte[] zip = storedWithDescriptors("a.txt", "the original content of the file");
        zip[30 + "a.txt".length() + 4] ^= 0x20; // a byte inside the stored data

        ZipArchiveCheck.Result result = check(zip);
        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("damaged");
    }

    @Test
    void aCompressionMethodThatCantBeChecked_isRefusedWithItsOwnMessage() {
        byte[] zip = storedWithDescriptors("big.bin", "pretend this is Deflate64 data");
        zip[lastIndexOf(zip, 0x02014b50) + 10] = 9; // central directory: method 9, Deflate64

        ZipArchiveCheck.Result result = check(zip);
        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("compression method");
    }

    @Test
    void containsEntry_readsTheIndex_whateverOrderThePartsCameIn() throws IOException {
        byte[] pptx = zip("ppt/slides/slide1.xml", "<slide/>", "[Content_Types].xml", "<Types/>");
        assertThat(ZipArchiveCheck.containsEntry(new ByteArrayInputStream(pptx), "[Content_Types].xml"))
                .isEqualTo(ZipArchiveCheck.Presence.PRESENT);

        byte[] plain = zip("ppt/slides/slide1.xml", "<slide/>");
        assertThat(ZipArchiveCheck.containsEntry(new ByteArrayInputStream(plain), "[Content_Types].xml"))
                .isEqualTo(ZipArchiveCheck.Presence.ABSENT);

        byte[] cut = Arrays.copyOf(pptx, pptx.length / 2);
        assertThat(ZipArchiveCheck.containsEntry(new ByteArrayInputStream(cut), "[Content_Types].xml"))
                .isEqualTo(ZipArchiveCheck.Presence.UNREADABLE);
    }

    @Test
    void notAZipAtAll_isRefused() {
        byte[] text = "PK\u0003\u0004 but then nothing a ZIP reader understands".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(check(text).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }
}
