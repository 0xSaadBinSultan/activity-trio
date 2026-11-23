package dev.saad.activitytrio;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.util.SizeF;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAppWidgetManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetRefreshTest {
    Application context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();Store.prefs(context).edit().clear().apply();for(String id:ActivityData.PROVIDERS)new java.io.File(context.getFilesDir(),id+".json").delete();TestFixtures.cache(context);RejectingManager.brokenId=-1;}
    Bitmap image(View view,int id){android.graphics.drawable.Drawable drawable=((ImageView)view.findViewById(id)).getDrawable();return drawable instanceof BitmapDrawable?((BitmapDrawable)drawable).getBitmap():null;}
    int[] pixels(Bitmap bitmap){
        int[] result=new int[bitmap.getWidth()*bitmap.getHeight()];
        bitmap.getPixels(result,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
        return result;
    }
    RemoteViews roundtrip(RemoteViews views){Parcel parcel=Parcel.obtain();try{views.writeToParcel(parcel,0);parcel.setDataPosition(0);return RemoteViews.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}}
    @Test public void combinedGraphPixelsUpdateAfterParcelAndReapply()throws Exception{
        Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300);
        View view=roundtrip(Widgets.forOptions(context,5,TrioWidget.class,options)).apply(context,new FrameLayout(context));
        // Snapshot pixel values before reapply; do not rely on native bitmap
        // copy/sameAs behavior or on a buffer that the host may later reuse.
        int[] before=pixels(image(view,R.id.github_graph));
        int[] other=pixels(image(view,R.id.leetcode_graph));
        ActivityData previous=Store.read(context,"github");TreeMap<LocalDate,ActivityData.Day> days=new TreeMap<>(previous.cells);days.put(LocalDate.of(2026,1,1),new ActivityData.Day(40,4));Store.save(context,new ActivityData("github",previous.username,previous.year,previous.total+37,previous.updated,previous.zone,false,days));
        roundtrip(Widgets.forOptions(context,5,TrioWidget.class,options)).reapply(context,view);
        assertEquals(Integer.valueOf(40),Store.read(context,"github").day(LocalDate.of(2026,1,1)).count);
        assertFalse("Updated GitHub data must change the rendered pixel values",Arrays.equals(before,pixels(image(view,R.id.github_graph))));
        assertArrayEquals("Unchanged LeetCode pixel values must remain identical",other,pixels(image(view,R.id.leetcode_graph)));
        assertNotNull(image(view,R.id.codeforces_graph));
    }
    @Test public void sixteenLauncherSizesStayWithinActualBitmapPayloadBudget()throws Exception{
        Bundle options=new Bundle();ArrayList<SizeF> sizes=new ArrayList<>();for(int i=0;i<16;i++)sizes.add(new SizeF(320+i*120,300+i*70));options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,sizes);
        RemoteViews views=Widgets.forOptions(context,5,TrioWidget.class,options);Method estimate=RemoteViews.class.getDeclaredMethod("estimateMemoryUsage");estimate.setAccessible(true);long bytes=((Number)estimate.invoke(views)).longValue();assertTrue(bytes>0);assertTrue(bytes<=Widgets.bitmapBudget(context));
        View view=roundtrip(views).apply(context,new FrameLayout(context));long visibleBytes=0;for(int id:new int[]{R.id.github_graph,R.id.codeforces_graph,R.id.leetcode_graph})visibleBytes+=image(view,id).getAllocationByteCount();assertTrue(visibleBytes<=Widgets.bitmapBudget(context));
    }
    @Test public void reopeningAppRepublishesCachedCombinedGraphs(){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);ShadowAppWidgetManager host=Shadows.shadowOf(manager);int id=host.createWidget(TrioWidget.class,R.layout.widget_trio);RemoteViews empty=new RemoteViews(context.getPackageName(),R.layout.widget_trio);empty.setImageViewBitmap(R.id.github_graph,null);empty.setImageViewBitmap(R.id.leetcode_graph,null);manager.updateAppWidget(id,empty);assertNull(image(host.getViewFor(id),R.id.github_graph));
        try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){assertNotNull(image(host.getViewFor(id),R.id.github_graph));assertNotNull(image(host.getViewFor(id),R.id.leetcode_graph));}
    }
    @Implements(AppWidgetManager.class)
    public static class RejectingManager extends ShadowAppWidgetManager {
        static int brokenId=-1;
        @Implementation @Override protected void updateAppWidget(int id,RemoteViews views){if(id==brokenId)throw new IllegalArgumentException("Simulated host rejection");super.updateAppWidget(id,views);}
    }
    @Test @Config(shadows=RejectingManager.class) public void oneRejectedWidgetDoesNotBlockOtherProviders(){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);ShadowAppWidgetManager host=Shadows.shadowOf(manager);int combined=host.createWidget(TrioWidget.class,R.layout.widget_trio),single=host.createWidget(GithubWidget.class,R.layout.widget_single);manager.updateAppWidget(single,new RemoteViews(context.getPackageName(),R.layout.widget_single));RejectingManager.brokenId=combined;
        Widgets.updateAll(context);assertNotNull(image(host.getViewFor(single),R.id.widget_graph));assertEquals(1,Store.prefs(context).getInt("widget-update-failures",-1));
    }
    @Test public void successfulFeedAppearsBeforeOtherFeedsFinish()throws Exception{
        for(String id:ActivityData.PROVIDERS)new java.io.File(context.getFilesDir(),id+".json").delete();AppWidgetManager manager=AppWidgetManager.getInstance(context);ShadowAppWidgetManager host=Shadows.shadowOf(manager);int id=host.createWidget(TrioWidget.class,R.layout.widget_trio);int[] published={0};
        new SyncEngine(context).refresh(url->{if(url.startsWith("https://github.com/"))return "<td id='day' data-date='2026-01-01' data-level='2'></td><tool-tip for='day'>3 contributions</tool-tip>";assertNotNull(image(host.getViewFor(id),R.id.github_graph));throw new IOException("Simulated slow/unavailable provider");},(url,body)->{assertNotNull(image(host.getViewFor(id),R.id.github_graph));return TestFixtures.leetcode().toString();},()->{published[0]++;Widgets.updateAll(context);});
        assertEquals(3,published[0]);assertNotNull(image(host.getViewFor(id),R.id.leetcode_graph));assertNull(Store.read(context,"codeforces"));assertFalse(Store.error(context,"codeforces").isEmpty());
    }
    @Test public void coldCacheFailureIsVisibleAndRemovedServiceIsAbsent(){
        for(String id:ActivityData.PROVIDERS)new java.io.File(context.getFilesDir(),id+".json").delete();Store.error(context,"github","Service returned HTTP 429 · no activity cached yet");
        try(org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){String text=ActivityTrioTest.allText(c.get().getWindow().getDecorView());assertTrue(text.contains("HTTP 429"));assertFalse(text.contains("TryHackMe"));assertFalse(text.contains("Import snapshot"));}
    }
}
