package com.majnioui.tv.prayer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class AzanSoundTest {
    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    @Test
    public void missingFileIsNotReady() {
        assertFalse(AzanSound.ready(new File(dir.getRoot(), AzanSound.NAME)));
    }

    @Test
    public void folderIsNotReady() throws Exception {
        assertFalse(AzanSound.ready(dir.newFolder(AzanSound.NAME)));
    }

    @Test
    public void tinyFileIsNotReady() throws Exception {
        File f = dir.newFile(AzanSound.NAME);
        Files.write(f.toPath(), new byte[100]);
        assertFalse(AzanSound.ready(f));
    }

    @Test
    public void realSizedFileIsReady() throws Exception {
        File f = dir.newFile(AzanSound.NAME);
        Files.write(f.toPath(), new byte[64 * 1024]);
        assertTrue(AzanSound.ready(f));
    }
}
