package dev.prototype.lovense;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Local recordings contain normalized gestures, never device addresses or session authority. */
final class SpellBook {
    static final long MAX_DURATION = 120_000;
    static final int MAX_FRAMES = 6000, MAX_SPELLS = 32;
    static final class Frame {
        final long time; final double[] values; final int touching;
        Frame(long time, double[] values, int touching) { this.time=time; this.values=values.clone(); this.touching=touching; }
    }
    static final class Spell {
        final String id, name; final int sticker, channels; final long duration; final List<Frame> frames;
        Spell(String id,String name,int sticker,int channels,long duration,List<Frame> frames) {
            this.id=id; this.name=name; this.sticker=sticker; this.channels=channels; this.duration=duration;
            this.frames=Collections.unmodifiableList(new ArrayList<>(frames));
            validate(this);
        }
        double[] at(long time) {
            if(time<0 || time>=duration)return new double[channels];
            int lo=0,hi=frames.size()-1;
            while(lo<hi){int mid=(lo+hi+1)/2;if(frames.get(mid).time<=time)lo=mid;else hi=mid-1;}
            Frame a=frames.get(lo); double[] result=a.values.clone();
            if(lo+1<frames.size()){
                Frame b=frames.get(lo+1); double ratio=(time-a.time)/(double)(b.time-a.time);
                for(int i=0;i<channels;i++)if((a.touching&b.touching&(1<<i))!=0)
                    result[i]=TouchModel.normalize(a.values[i]+(b.values[i]-a.values[i])*ratio,0);
            }
            return result;
        }
    }
    static final class Recording {
        final int channels; final long started; final ArrayList<Frame> frames=new ArrayList<>();
        Recording(int channels,long now){if(channels<1||channels>2)throw new IllegalArgumentException("Choose a device first");this.channels=channels;started=now;frames.add(new Frame(0,new double[channels],0));}
        boolean add(long now,double[] values,int touching){
            long time=Math.max(1,now-started);
            if(time>=MAX_DURATION||frames.size()>=MAX_FRAMES-1)return false;
            checkValues(values,channels);Frame previous=frames.get(frames.size()-1);
            Frame next=new Frame(time,values,touching);
            // Keep both ends of a stationary span. Repeated samples advance its last anchor,
            // so later motion never invents a ramp through a long stationary finger dwell.
            boolean stationary=Arrays.equals(previous.values,values)&&previous.touching==touching;
            Frame before=frames.size()>1?frames.get(frames.size()-2):null;
            if(previous.time==time || stationary && before!=null && Arrays.equals(before.values,values)&&before.touching==touching)
                frames.set(frames.size()-1,next);
            else frames.add(next);
            return true;
        }
        Spell finish(long now,String name,int sticker){
            long end=Math.min(MAX_DURATION,Math.max(frames.get(frames.size()-1).time+1,now-started));
            ArrayList<Frame> complete=new ArrayList<>(frames);complete.add(new Frame(end,new double[channels],0));
            return new Spell(UUID.randomUUID().toString(),name.trim(),sticker,channels,end,complete);
        }
        boolean hasMovement(){for(Frame f:frames)for(double v:f.values)if(v>TouchModel.REST_LIMIT)return true;return false;}
    }
    private final android.content.SharedPreferences prefs;
    SpellBook(Context context){prefs=context.getSharedPreferences("spellbook",Context.MODE_PRIVATE);}
    List<Spell> all(){
        ArrayList<Spell> spells=new ArrayList<>();String data=prefs.getString("spells","[]");
        if(data==null||data.length()>16_000_000)return spells;
        try{JSONArray list=new JSONArray(data);for(int i=0;i<Math.min(list.length(),MAX_SPELLS);i++)try{spells.add(decode(list.getJSONObject(i)));}catch(JSONException|IllegalArgumentException ignored){}}
        catch(JSONException ignored){} return spells;
    }
    void save(Spell spell){List<Spell> list=all();if(list.size()>=MAX_SPELLS)throw new IllegalStateException("Your Spellbook is full. Delete a spell to make room.");list.add(0,spell);write(list);}
    void delete(String id){List<Spell> list=all();list.removeIf(s->s.id.equals(id));write(list);}
    private void write(List<Spell> spells){JSONArray list=new JSONArray();for(Spell s:spells)list.put(encode(s));if(!prefs.edit().putString("spells",list.toString()).commit())throw new IllegalStateException("Could not save your Spellbook. Please try again.");}
    static JSONObject encode(Spell s){try{JSONArray frames=new JSONArray();for(Frame f:s.frames)frames.put(new JSONObject().put("t",f.time).put("v",new JSONArray(f.values)).put("touch",f.touching));return new JSONObject().put("id",s.id).put("name",s.name).put("sticker",s.sticker).put("channels",s.channels).put("duration",s.duration).put("frames",frames);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    static Spell decode(JSONObject object)throws JSONException{
        int channels=object.getInt("channels");JSONArray data=object.getJSONArray("frames");if(data.length()>MAX_FRAMES)throw new IllegalArgumentException("Too many gestures");
        ArrayList<Frame> frames=new ArrayList<>();for(int i=0;i<data.length();i++){JSONObject f=data.getJSONObject(i);JSONArray v=f.getJSONArray("v");if(v.length()!=channels||channels<1||channels>2)throw new IllegalArgumentException("Invalid channels");double[] values=new double[channels];for(int j=0;j<channels;j++)values[j]=v.getDouble(j);frames.add(new Frame(f.getLong("t"),values,f.getInt("touch")));}
        return new Spell(object.getString("id"),object.getString("name"),object.getInt("sticker"),channels,object.getLong("duration"),frames);
    }
    private static void validate(Spell s){
        if(s.id.isEmpty()||s.id.length()>80||s.name.trim().isEmpty()||s.name.length()>40||s.sticker<0||s.sticker>2||s.channels<1||s.channels>2||s.duration<1||s.duration>MAX_DURATION||s.frames.size()<2||s.frames.size()>MAX_FRAMES)throw new IllegalArgumentException("Invalid spell");
        long previous=-1;for(Frame f:s.frames){checkValues(f.values,s.channels);if(f.time<=previous||f.time>s.duration||f.touching<0||f.touching>=(1<<s.channels))throw new IllegalArgumentException("Invalid gesture");previous=f.time;}
        if(s.frames.get(0).time!=0||previous!=s.duration)throw new IllegalArgumentException("Invalid duration");
        for(double v:s.frames.get(0).values)if(v!=0)throw new IllegalArgumentException("Spell must start at rest");
        for(double v:s.frames.get(s.frames.size()-1).values)if(v!=0)throw new IllegalArgumentException("Spell must finish at rest");
    }
    private static void checkValues(double[] values,int count){if(values==null||values.length!=count)throw new IllegalArgumentException("Invalid channels");for(double v:values)if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Invalid level");}
}
