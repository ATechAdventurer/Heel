package dev.prototype.lovense;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SpellBookTest {
    private Context context;
    @Before public void clear(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("spellbook",0).edit().clear().commit();}
    static SpellBook.Spell sample(int channels){
        double[] a=new double[channels],b=new double[channels];Arrays.fill(a,.2);Arrays.fill(b,.8);
        return new SpellBook.Spell(UUID.randomUUID().toString(),"Fireflies",1,channels,1000,List.of(
            new SpellBook.Frame(0,new double[channels],0),new SpellBook.Frame(100,a,(1<<channels)-1),
            new SpellBook.Frame(300,b,(1<<channels)-1),new SpellBook.Frame(400,b,0),new SpellBook.Frame(1000,new double[channels],0)));
    }
    @Test public void motionInterpolatesButHeldAndReleaseTransitionsStayExact(){
        SpellBook.Spell spell=sample(2);
        assertArrayEquals(new double[]{0,0},spell.at(99),0);
        assertArrayEquals(new double[]{.5,.5},spell.at(200),.00001);
        assertArrayEquals(new double[]{.8,.8},spell.at(999),0);
        assertArrayEquals(new double[]{0,0},spell.at(1000),0);
    }
    @Test public void continuousRecordingRoundTripsAcrossStoreInstancesAndDeletes(){
        SpellBook.Recording record=new SpellBook.Recording(1,100);
        record.add(110,new double[]{.333},1);record.add(450,new double[]{.777},1);record.add(500,new double[]{.777},0);
        SpellBook.Spell spell=record.finish(1000,"  My little spell  ",2);
        new SpellBook(context).save(spell);SpellBook.Spell loaded=new SpellBook(context).all().get(0);
        assertEquals("My little spell",loaded.name);assertEquals(2,loaded.sticker);assertEquals(900,loaded.duration);
        assertEquals(.333,loaded.frames.get(1).values[0],0);assertArrayEquals(new double[]{0},loaded.at(900),0);
        new SpellBook(context).delete(spell.id);assertTrue(new SpellBook(context).all().isEmpty());
    }
    @Test public void boundedRecordingKeepsZeroAtBothEnds(){
        SpellBook.Recording record=new SpellBook.Recording(1,0);
        record.add(1,new double[]{.4},1);assertFalse(record.add(SpellBook.MAX_DURATION,new double[]{.8},1));
        SpellBook.Spell spell=record.finish(SpellBook.MAX_DURATION+100,"End",0);
        assertEquals(SpellBook.MAX_DURATION,spell.duration);assertEquals(0,spell.frames.get(0).values[0],0);
        assertEquals(0,spell.frames.get(spell.frames.size()-1).values[0],0);
    }
    @Test public void stationaryFingerDwellDoesNotBecomeALongRamp(){
        SpellBook.Recording record=new SpellBook.Recording(1,0);
        record.add(100,new double[]{.2},1);
        for(int t=150;t<=2100;t+=50)record.add(t,new double[]{.2},1);
        record.add(2116,new double[]{.8},1);
        SpellBook.Spell spell=record.finish(2200,"Still",0);
        assertEquals(.2,spell.at(1000)[0],0);assertEquals(.2,spell.at(2100)[0],0);
        assertEquals(.5,spell.at(2108)[0],.00001);assertTrue(spell.frames.size()<10);
    }
    @Test public void corruptEntryCannotPreventOtherSpellsLoading()throws Exception{
        JSONArray list=new JSONArray().put(new JSONObject().put("name","broken")).put(SpellBook.encode(sample(1)));
        context.getSharedPreferences("spellbook",0).edit().putString("spells",list.toString()).commit();
        assertEquals(1,new SpellBook(context).all().size());
        JSONObject dangerous=SpellBook.encode(sample(1));dangerous.getJSONArray("frames").getJSONObject(1).put("v",new JSONArray().put(9));
        assertThrows(IllegalArgumentException.class,()->SpellBook.decode(dangerous));
    }
    @Test public void invalidTimesAndNonzeroStartAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new SpellBook.Spell("id","Name",0,1,100,List.of(new SpellBook.Frame(0,new double[]{.5},0),new SpellBook.Frame(100,new double[]{0},0))));
        assertThrows(IllegalArgumentException.class,()->new SpellBook.Spell("id","Name",0,1,100,List.of(new SpellBook.Frame(0,new double[]{0},0),new SpellBook.Frame(0,new double[]{.5},0),new SpellBook.Frame(100,new double[]{0},0))));
    }
    @Test public void libraryCapacityDoesNotSilentlyLoseOldSpells(){
        SpellBook book=new SpellBook(context);for(int i=0;i<SpellBook.MAX_SPELLS;i++)book.save(sample(1));
        assertThrows(IllegalStateException.class,()->book.save(sample(1)));assertEquals(32,new SpellBook(context).all().size());
    }
}
