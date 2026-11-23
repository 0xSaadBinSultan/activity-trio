package dev.saad.activitytrio;

import android.app.Application;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ActivityTrioTest {
    private Application context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();
        // Android grants this app-defined signature permission on installation.
        // Robolectric requires that grant to be modeled explicitly on API 28.
        org.robolectric.Shadows.shadowOf(context).grantPermissions(context.getPackageName()+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        Store.prefs(context).edit().clear().putInt("year",2026).apply();for(String p:ActivityData.PROVIDERS)new File(context.getFilesDir(),p+".json").delete();}
    private String cell(String id,String date,int level,String tip){return "<td id='"+id+"' data-date='"+date+"' data-level='"+level+"'></td><tool-tip for='"+id+"'>"+tip+"</tool-tip>";}
    @Test public void githubCountsAndZeroAndOtherYear(){ActivityData d=Parsers.github(cell("a","2026-01-01",4,"<b>1,234 contributions</b> on Jan 1")+cell("b","2026-01-02",0,"No contributions on Jan 2")+cell("c","2025-01-01",4,"5 contributions"),"user",2026,"UTC","2026-11-14T12:00:00Z");assertEquals(1234,d.total);assertEquals(2,d.cells.size());assertEquals(Integer.valueOf(0),d.day(LocalDate.of(2026,1,2)).count);}
    @Test public void githubSingleContribution(){assertEquals(1,Parsers.github(cell("a","2026-01-01",1,"1 contribution on Jan 1"),"a",2026,"UTC","2026-01-01T00:00:00Z").total);}
    @Test(expected=IllegalArgumentException.class) public void githubDoesNotInventMissingCounts(){Parsers.github(cell("a","2026-01-01",1,"format changed"),"a",2026,"UTC","2026-01-01T00:00:00Z");}
    @Test(expected=IllegalArgumentException.class) public void githubEmptyIsNotZero(){Parsers.github("<html>Rate limited</html>","a",2026,"UTC","2026-01-01T00:00:00Z");}
    private JSONObject submission(long id,String instant,String verdict)throws Exception{return new JSONObject().put("id",id).put("creationTimeSeconds",Instant.parse(instant).getEpochSecond()).put("verdict",verdict);}
    @Test public void codeforcesTimezoneDuplicatesAndUnsuccessfulAttempts()throws Exception{
        JSONArray rows=new JSONArray().put(submission(1,"2026-01-01T04:30:00Z","OK")).put(submission(2,"2026-01-01T05:30:00Z","WRONG_ANSWER")).put(submission(2,"2026-01-01T05:30:00Z","WRONG_ANSWER")).put(submission(3,"2027-01-01T04:59:00Z","COMPILATION_ERROR")).put(submission(4,"2027-01-01T05:01:00Z","OK"));
        ActivityData d=Parsers.codeforces(rows,"demo-user",2026,"America/New_York","2026-11-14T12:00:00Z");assertEquals(2,d.total);assertEquals(Integer.valueOf(1),d.day(LocalDate.of(2026,1,1)).count);assertEquals(Integer.valueOf(1),d.day(LocalDate.of(2026,12,31)).count);assertEquals(Integer.valueOf(0),d.day(LocalDate.of(2026,6,1)).count);
    }
    @Test public void freshInstallHasNoUsernamesOrBundledProfileCaches()throws Exception{for(String id:ActivityData.PROVIDERS){assertEquals("",Store.user(context,id));assertNull(Store.read(context,id));}assertFalse(Store.configured(context));assertEquals(ZoneId.systemDefault().getId(),Store.zone(context));for(String name:context.getAssets().list(""))assertFalse(name.endsWith("-seed.json")||name.equals("tryhackme-snapshot.json"));}
    @Test public void firstLaunchAsksForUsernamesAndAllowsOnlyLeetcode()throws Exception{
        try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotNull(dialog);
            ArrayList<EditText> fields=new ArrayList<>();findEdits(dialog.getWindow().getDecorView(),fields);assertEquals(5,fields.size());for(int i=0;i<3;i++)assertEquals("",fields.get(i).getText().toString());
            fields.get(2).setText("demo-user");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing());assertEquals("demo-user",Store.user(context,"leetcode"));assertEquals("",Store.user(context,"github"));assertEquals("",Store.user(context,"codeforces"));assertTrue(Store.configured(context));
        }
    }
    private static void findEdits(View view,List<EditText> result){if(view instanceof EditText)result.add((EditText)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)findEdits(((ViewGroup)view).getChildAt(i),result);}
    @Test public void jsonRoundtripPreservesLiveCounts()throws Exception{TestFixtures.cache(context);ActivityData a=Store.read(context,"leetcode"),b=ActivityData.fromJson(a.json());assertEquals(a.json().toString(),b.json().toString());assertEquals(Integer.valueOf(7),b.day(LocalDate.of(2026,1,1)).count);}
    @Test public void repeatedCacheSavesReplaceDataAndPreserveOtherProviders()throws Exception{
        TestFixtures.cache(context);
        ActivityData original=Store.read(context,"github");
        String other=Store.read(context,"codeforces").json().toString();
        LocalDate day=LocalDate.of(2026,1,1);
        for(int count:new int[]{0,7,40}){
            TreeMap<LocalDate,ActivityData.Day> days=new TreeMap<>(original.cells);
            days.put(day,new ActivityData.Day(count,count==0?0:4));
            Store.save(context,new ActivityData("github",original.username,original.year,count,
                original.updated,original.zone,false,days));
            assertEquals(Integer.valueOf(count),Store.read(context,"github").day(day).count);
            assertEquals(other,Store.read(context,"codeforces").json().toString());
        }
        for(java.io.File entry:context.getFilesDir().listFiles())
            assertFalse("Successful writes must clean up temporary files",entry.getName().endsWith(".pending"));
    }
    @Test public void switchingProfileDoesNotDisplayPreviousUsersGraph(){TestFixtures.cache(context);assertNotNull(Store.read(context,"github"));Store.prefs(context).edit().putString("github","another-person").apply();assertNull(Store.read(context,"github"));}
    @Test public void upgradeKeepsRemainingProfilesAndRemovesRetiredProvider()throws Exception{
        TestFixtures.cache(context);Store.prefs(context).edit().putString("tryhackme","legacy-profile").apply();
        try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            assertArrayEquals(new String[]{"github","codeforces","leetcode"},ActivityData.PROVIDERS);
            for(String id:ActivityData.PROVIDERS){assertEquals(TestFixtures.USER,Store.user(context,id));assertNotNull(Store.read(context,id));}
            assertFalse(allText(c.get().getWindow().getDecorView()).contains("TryHackMe"));
            try{context.getPackageManager().getReceiverInfo(new ComponentName(context,"dev.saad.activitytrio.TryHackMeWidget"),0);fail("Retired widget still registered");}catch(android.content.pm.PackageManager.NameNotFoundException expected){}
        }
    }
    @Test public void switchingYearRejectsOldLiveGraphs(){TestFixtures.cache(context);Store.prefs(context).edit().putInt("year",2025).apply();for(String id:ActivityData.PROVIDERS)assertNull(Store.read(context,id));}
    @Test(expected=IOException.class) public void importSizeBounded()throws Exception{Store.bytes(new ByteArrayInputStream(new byte[257]),256);}
    @Test public void everyDateHitsItsSquareAtMultipleAspectRatios(){for(int year:new int[]{2024,2026})for(int[] size:new int[][]{{320,90},{1000,180},{250,500},{640,120},{1200,480}}){Graphs.Layout g=new Graphs.Layout(year,size[0],size[1],1);for(LocalDate d=LocalDate.of(year,1,1);d.getYear()==year;d=d.plusDays(1)){long index=d.toEpochDay()-g.start.toEpochDay();assertEquals(d,g.hit(g.x+(index/7+.4f)*g.step,g.y+(index%7+.4f)*g.step));}assertNull(g.hit(-1,-1));assertTrue(g.x>=19);assertTrue(g.y>=18);assertTrue(g.x+g.weeks*g.step<=size[0]+.01f);assertTrue(g.y+7*g.step<=size[1]+.01f);}}
    @Test public void widgetLayoutsAndResizeRenderWithoutDataAndWithCache(){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);org.robolectric.shadows.ShadowAppWidgetManager shadow=org.robolectric.Shadows.shadowOf(manager);
        @SuppressWarnings("unchecked") Class<? extends android.appwidget.AppWidgetProvider>[] types=new Class[]{TrioWidget.class,GithubWidget.class,CodeforcesWidget.class,LeetcodeWidget.class};
        for(boolean seed:new boolean[]{false,true}){
            for(String provider:ActivityData.PROVIDERS)Store.prefs(context).edit().putString(provider,seed?"demo-user":"uncached-user").apply();
            if(seed)TestFixtures.cache(context);
            for(int i=0;i<types.length;i++){
                int id=shadow.createWidget(types[i],types[i]==TrioWidget.class?R.layout.widget_trio:R.layout.widget_single);Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,280+i*70);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,types[i]==TrioWidget.class?340:140);manager.updateAppWidgetOptions(id,options);Widgets.update(context,manager,id,types[i]);
                View view=shadow.getViewFor(id);String text=allText(view);assertTrue(seed?!text.contains("waiting for data"):text.contains("waiting for data"));if(seed&&types[i]==TrioWidget.class){assertTrue(text.contains("2190 contributions"));assertNotNull(((ImageView)view.findViewById(R.id.github_graph)).getDrawable());}
            }
        }
    }
    @Test public void refreshRequestsAreThrottled(){Store.prefs(context).edit().putString("github","demo-user").apply();assertTrue(Widgets.refresh(context));assertFalse(Widgets.refresh(context));}
    @Test public void dashboardOpensLeetcodeDetails(){TestFixtures.cache(context);try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("provider","leetcode")).setup()){String text=allText(c.get().getWindow().getDecorView());assertTrue(text.contains("23/4000 solved"));assertTrue(text.contains("Medium 12/2000"));assertFalse(text.contains("TryHackMe"));}}
    static String allText(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(allText(((ViewGroup)v).getChildAt(i)));return b.toString();}
}
