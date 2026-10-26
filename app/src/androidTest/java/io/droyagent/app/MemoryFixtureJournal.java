package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Test-only recovery journal committed before mutating preferences or moving data. */
final class MemoryFixtureJournal {
    interface Cleanup { void delete(File path) throws Exception; }
    private final Context context;
    final File file;
    private final JSONObject state;
    private MemoryFixtureJournal(Context context,File file,JSONObject state){this.context=context;this.file=file;this.state=state;}
    static MemoryFixtureJournal create(Context context,File file,String... preferenceNames) throws Exception {
        if(file.exists())throw new IOException("Restore the unfinished memory fixture before running another one");
        JSONObject snapshots=new JSONObject();
        for(String name:preferenceNames){
            JSONObject entries=new JSONObject();
            for(var entry:context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll().entrySet()){
                Object value=entry.getValue();String type;
                if(value instanceof Boolean)type="boolean";else if(value instanceof String)type="string";else if(value instanceof Integer)type="int";
                else if(value instanceof Long)type="long";else if(value instanceof Float)type="float";else if(value instanceof Set<?>)type="set";
                else throw new IOException("Unsupported fixture preference type");
                entries.put(entry.getKey(),new JSONObject().put("type",type).put("value",value instanceof Set<?> values?new JSONArray(values):value));
            }
            snapshots.put(name,entries);
        }
        MemoryFixtureJournal journal=new MemoryFixtureJournal(context,file,new JSONObject().put("version",1).put("fixtureId",UUID.randomUUID().toString())
            .put("phase","prepared").put("preferences",snapshots).put("paths",new JSONObject()));
        journal.save();return journal;
    }
    static MemoryFixtureJournal load(Context context,File file) throws Exception {
        if(file.length()>1048576||Files.isSymbolicLink(file.toPath()))throw new IOException("Invalid fixture journal");
        JSONObject state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        if(state.getInt("version")!=1||!state.getString("fixtureId").matches("[0-9a-f-]{36}"))throw new IOException("Invalid fixture journal");
        return new MemoryFixtureJournal(context,file,state);
    }
    String fixtureId() throws Exception{return state.getString("fixtureId");}
    private File named(String name) throws IOException {
        if(!name.matches("[A-Za-z0-9._-]{1,160}"))throw new IOException("Invalid fixture recovery path");
        return new File(context.getFilesDir(),name);
    }
    void preserve(String name) throws Exception {
        File current=named(name);String backup="memory-product-preserved-"+UUID.randomUUID();
        boolean existed=Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS);
        state.getJSONObject("paths").put(name,new JSONObject().put("existed",existed).put("backup",backup));save();
        if(existed&&!current.renameTo(named(backup)))throw new IOException("Could not preserve fixture data");
    }
    private void save() throws Exception {
        File temporary=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write((state.toString()+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    void restore(Cleanup cleanup) throws Exception {
        List<Throwable> failures=new ArrayList<>();JSONObject paths=state.getJSONObject("paths");
        for(Iterator<String> keys=paths.keys();keys.hasNext();){String name=keys.next();try{
            JSONObject entry=paths.getJSONObject(name);File current=named(name),backup=named(entry.getString("backup"));
            if(Files.exists(backup.toPath(),LinkOption.NOFOLLOW_LINKS)){
                if(Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS))cleanup.delete(current);
                if(!backup.renameTo(current))throw new IOException("Could not restore preserved data");
            }else if(!entry.getBoolean("existed")){
                if(Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS))cleanup.delete(current);
            }else if(!Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Preserved data is missing");
        }catch(Throwable failure){failures.add(failure);}}
        // A file failure must never prevent restoring collection/protection or
        // display/background choices. Leave the journal for a later retry.
        JSONObject snapshots=state.getJSONObject("preferences");
        for(Iterator<String> names=snapshots.keys();names.hasNext();){String name=names.next();try{
            SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);SharedPreferences.Editor edit=prefs.edit().clear();
            JSONObject entries=snapshots.getJSONObject(name);Map<String,Object> expected=new HashMap<>();
            for(Iterator<String> keys=entries.keys();keys.hasNext();){String key=keys.next();JSONObject entry=entries.getJSONObject(key);Object value;
                switch(entry.getString("type")){
                    case "boolean":value=entry.getBoolean("value");edit.putBoolean(key,(Boolean)value);break;
                    case "string":value=entry.getString("value");edit.putString(key,(String)value);break;
                    case "int":value=entry.getInt("value");edit.putInt(key,(Integer)value);break;
                    case "long":value=entry.getLong("value");edit.putLong(key,(Long)value);break;
                    case "float":value=(float)entry.getDouble("value");edit.putFloat(key,(Float)value);break;
                    case "set":Set<String> values=new HashSet<>();JSONArray array=entry.getJSONArray("value");for(int i=0;i<array.length();i++)values.add(array.getString(i));value=values;edit.putStringSet(key,values);break;
                    default:throw new IOException("Invalid saved preference type");
                }
                expected.put(key,value);
            }
            if(!edit.commit()||!prefs.getAll().equals(expected))throw new IOException("Preference recovery verification failed");
        }catch(Throwable failure){failures.add(failure);}}
        if(!failures.isEmpty()){
            state.put("phase","restore_failed");save();IOException error=new IOException("Memory fixture recovery is incomplete; durable journal retained");
            for(Throwable failure:failures)error.addSuppressed(failure);throw error;
        }
        Files.delete(file.toPath());
    }
}
