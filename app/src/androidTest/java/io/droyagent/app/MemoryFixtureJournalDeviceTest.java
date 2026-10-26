package io.droyagent.app;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoryFixtureJournalDeviceTest {
    @Test public void directoryFailureStillRestoresPreferencesAndRestartCanFinishRecovery() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String id="memory-journal-test-"+UUID.randomUUID();
        File directory=new File(base.getCacheDir(),id);assertTrue(directory.mkdirs());
        Context isolated=new ContextWrapper(base){
            @Override public File getFilesDir(){return directory;}
            @Override public SharedPreferences getSharedPreferences(String ignored,int mode){return base.getSharedPreferences(id,mode);}
        };
        SharedPreferences prefs=isolated.getSharedPreferences("product",Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putBoolean("fixtureChoice",true).putString("fixtureLabel","original synthetic choice").commit());
        Map<String,?> before=new HashMap<>(prefs.getAll());File original=new File(directory,"synthetic-data");assertTrue(original.mkdirs());
        Files.write(new File(original,"original.txt").toPath(),new byte[]{1,2,3});
        File journalFile=new File(directory,"restore.json");
        try {
            MemoryFixtureJournal journal=MemoryFixtureJournal.create(isolated,journalFile,"product");journal.preserve("synthetic-data");
            assertTrue(original.mkdirs());Files.write(new File(original,"temporary.txt").toPath(),new byte[]{4});
            assertTrue(prefs.edit().clear().putBoolean("fixtureChoice",false).commit());
            try{journal.restore(path->{throw new IOException("Synthetic inaccessible root-owned directory");});fail();}
            catch(IOException expected){assertTrue(journalFile.isFile());}
            assertEquals(before,prefs.getAll());assertTrue(new File(original,"temporary.txt").isFile());
            MemoryFixtureJournal.load(isolated,journalFile).restore(path->HistoryData.deleteTree(path.toPath()));
            assertFalse(journalFile.exists());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(original,"original.txt").toPath()));assertEquals(before,prefs.getAll());
        }finally{base.deleteSharedPreferences(id);HistoryData.deleteTree(directory.toPath());}
    }
}
