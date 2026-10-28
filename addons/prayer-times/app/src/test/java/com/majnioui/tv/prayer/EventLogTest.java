package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EventLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void appendsLines() throws IOException {
        File f = new File(tmp.getRoot(), EventLog.NAME);
        EventLog.add(f, "one\n");
        EventLog.add(f, "two\n");
        assertEquals("one\ntwo\n", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void staysUnderTheCapAndKeepsWholeNewestLines() throws IOException {
        File f = new File(tmp.getRoot(), EventLog.NAME);
        for (int i = 0; i < 5_000; i++) EventLog.add(f, "line " + i + " of the log\n");
        assertTrue(f.length() <= EventLog.MAX_BYTES + 64);
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.startsWith("line "));
        assertTrue(s.endsWith("line 4999 of the log\n"));
    }
}
