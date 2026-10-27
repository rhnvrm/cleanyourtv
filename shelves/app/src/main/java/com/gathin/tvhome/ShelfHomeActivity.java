package com.gathin.tvhome;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.ContentObserver;
import android.graphics.Color;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Personal local fork: TV-provider shelves beneath the existing saved dock. */
public final class ShelfHomeActivity extends Activity implements AppAdapter.TileListener {
    private Config config;
    private AppAdapter apps;
    private ShelfRepository repository;
    private ShelfImages images;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<Row> rows=new ArrayList<>();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView settings,heroTitle,photos;
    private FrameLayout root,heroSpace;
    private View ambient;
    private GlassView glass;
    private FrameLayout focusedArtwork;
    private SharedPreferences controls;
    private Map<String,Long> foregroundApps=new HashMap<>();
    private static final int PIN_COUNT=6;
    private boolean active,loading,dirty;
    private String focusedKey="";
    private long generation;
    private ShelfRepository.Data lastData;
    private final Runnable update=this::refresh;
    private final ContentObserver observer=new ContentObserver(handler){public void onChange(boolean self){if(active){handler.removeCallbacks(update);handler.postDelayed(update,1200);}}};
    private BroadcastReceiver packages;
    private static final int WHITE=0xfff7f7fa, MUTED=0xffaaaab6;
    private static final class Card {
        String key,title,subtitle; android.graphics.drawable.Drawable artwork;
        String image; Runnable action; ShelfRepository.Item media; int appIndex=-1; boolean recent;
    }
    private static final class Row {RecyclerView list; List<Card> cards; View filter; String group;}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Typeface regular,light,medium;
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(regular);t.setIncludeFontPadding(false);return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);regular=getResources().getFont(R.font.outfit_regular);light=getResources().getFont(R.font.outfit_light);medium=getResources().getFont(R.font.outfit_medium);
        PhotoScreensaver.recoverOnHome(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        controls=getSharedPreferences("shelf-controls",0);
        if(!controls.getBoolean("six-pins",false)){
            getSharedPreferences(Config.SETTINGS_NAME,0).edit().putInt("columns",PIN_COUNT).apply();
            controls.edit().putBoolean("six-pins",true).apply();
        }
        repository=new ShelfRepository(this);images=new ShelfImages(this);
        packages=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(active)refresh();}};
        IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);f.addAction(Intent.ACTION_PACKAGE_REPLACED);f.addDataScheme("package");registerReceiver(packages,f);
        createRoot();
    }
    private void createRoot(){
        // Reuse the actual original header, icons, typography, pill styles and floating glass.
        setContentView(R.layout.activity_main);root=findViewById(R.id.root);root.setBackgroundColor(Color.BLACK);
        findViewById(R.id.sky).setVisibility(View.GONE);findViewById(R.id.heroArt).setVisibility(View.GONE);
        findViewById(R.id.heroScrim).setVisibility(View.GONE);findViewById(R.id.btnInputs).setVisibility(View.GONE);
        findViewById(R.id.btnReorder).setVisibility(View.GONE);
        View originalHero=findViewById(R.id.hero);((ViewGroup)originalHero.getParent()).removeView(originalHero);
        TextView greeting=findViewById(R.id.greeting);int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour<5||hour>=22?"GOOD NIGHT":hour<12?"GOOD MORNING":hour<18?"GOOD AFTERNOON":"GOOD EVENING");
        ((TextView)findViewById(R.id.date)).setText(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM",Locale.getDefault())).toUpperCase(Locale.getDefault()));
        TextView network=findViewById(R.id.btnWifi);network.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        try {String ssid=((android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getSSID();if(ssid!=null&&!ssid.equals("<unknown ssid>"))network.setText(ssid.replace("\"",""));}catch(Exception ignored){}
        settings=findViewById(R.id.btnSettings);settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_SETTINGS)));settings.setOnLongClickListener(v->{menu();return true;});
        photos=findViewById(R.id.btnVpn);updatePhotoButton();photos.setText("Photos");photos.setContentDescription("Start Google Photos screensaver");photos.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_photo,0,0,0);photos.setOnClickListener(v->PhotoScreensaver.start(this));
        for(TextView pill:new TextView[]{network,photos,settings})pill.setOnFocusChangeListener((v,yes)->{pill.setTextColor(yes?0xff151515:WHITE);for(android.graphics.drawable.Drawable d:pill.getCompoundDrawablesRelative())if(d!=null)d.setTint(yes?0xff151515:WHITE);if(yes)glass.setAlpha(0f);});
        View oldGrid=findViewById(R.id.grid);FrameLayout wrapper=(FrameLayout)oldGrid.getParent();wrapper.removeView(oldGrid);
        scroll=new ScrollView(this);scroll.setId(View.generateViewId());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);scroll.setSmoothScrollingEnabled(false);scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,0,0,dp(42));content.setClipChildren(false);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));wrapper.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(heroSpace!=null){int want=Math.max(dp(120),b-t-dp(170));if(heroSpace.getLayoutParams().height!=want){heroSpace.getLayoutParams().height=want;heroSpace.requestLayout();}}});
        glass=findViewById(R.id.glass);glass.setStyle(Config.GLASS_DEFAULT);glass.setRadius(dp(12));glass.setAlpha(0f);glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ambient=findViewById(R.id.ambient);prepareGlow();
        root.getViewTreeObserver().addOnPreDrawListener(()->{positionGlass();return true;});
        View.OnKeyListener down=(v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_DOWN&&event.getAction()==KeyEvent.ACTION_DOWN&&!rows.isEmpty()){focus(0,0);return true;}return false;};settings.setOnKeyListener(down);network.setOnKeyListener(down);photos.setOnKeyListener(down);
    }
    private void prepareGlow(){
        // Same sampled radial mask as the original MainActivity, rendered once rather than every frame.
        android.graphics.Bitmap mask=android.graphics.Bitmap.createBitmap(480,270,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(mask);android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.RadialGradient(480*.32f,270*.42f,480*.62f,new int[]{0x5cffffff,0x26ffffff,0x00ffffff},null,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(0,0,480,270,paint);
        BitmapDrawable glow=new BitmapDrawable(getResources(),mask);glow.setGravity(Gravity.FILL);glow.setTint(0xffd02020);ambient.setBackground(glow);ambient.setAlpha(1f);
    }
    private void positionGlass(){
        if(focusedArtwork==null||!focusedArtwork.isAttachedToWindow()||getCurrentFocus()==null||!((View)focusedArtwork.getParent()).hasFocus()){glass.setAlpha(0f);return;}
        int[] art=new int[2],base=new int[2];focusedArtwork.getLocationOnScreen(art);root.getLocationOnScreen(base);
        ViewGroup.LayoutParams lp=glass.getLayoutParams();int w=focusedArtwork.getWidth()+dp(8),h=focusedArtwork.getHeight()+dp(8);
        if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;glass.setLayoutParams(lp);}
        glass.setTranslationX(art[0]-base[0]-dp(4));glass.setTranslationY(art[1]-base[1]-dp(4));glass.setAlpha(1f);
    }
    private void glowFor(Card card){
        if(heroTitle!=null)heroTitle.setText(card.title);
        String pkg=card.media!=null?card.media.pkg:apps.packageAt(card.appIndex);
        for(int i=0;i<apps.appCount();i++)if(pkg!=null&&pkg.equals(apps.packageAt(i))){android.graphics.drawable.Drawable d=ambient.getBackground();if(d!=null)d.setTint(0xff000000|apps.ambientColorAt(i));break;}
    }
    private TextView button(String label){
        TextView b=text(label,16,WHITE);b.setTypeface(medium);b.setGravity(Gravity.CENTER);b.setPadding(dp(20),dp(10),dp(20),dp(10));b.setFocusable(true);b.setClickable(true);b.setBackground(background(0xff202024,24));
        b.setOnFocusChangeListener((v,yes)->{b.setBackground(background(yes?0xffe7e7ef:0xff202024,24));b.setTextColor(yes?0xff141418:WHITE);});return b;
    }
    @Override protected void onResume(){super.onResume();PhotoScreensaver.recoverOnHome(this);updatePhotoButton();active=true;getContentResolver().registerContentObserver(Uri.parse("content://android.media.tv"),true,observer);refresh();}
    @Override protected void onPause(){active=false;handler.removeCallbacks(update);getContentResolver().unregisterContentObserver(observer);super.onPause();}
    @Override protected void onDestroy(){active=false;generation++;worker.shutdownNow();images.close();unregisterReceiver(packages);super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);focusedKey="";if(scroll!=null)scroll.scrollTo(0,0);if(!rows.isEmpty())focus(0,0);}
    private void refresh(){
        if(!active)return;if(loading){dirty=true;return;}loading=true;dirty=false;long request=++generation;
        config=Config.load(this);MainActivity.COLUMNS=PIN_COUNT;
        worker.execute(()->{
            AppAdapter loaded=new AppAdapter(this,this,PIN_COUNT,config);
            Order order=Order.load(Order.file(this));loaded.setSavedOrder(Order.ranks(order,config.dockApps),Order.docked(order,config.dockApps));
            loaded.reload(Collections.emptyList(),getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),0));
            ShelfRepository.Data data=repository.load();Map<String,Long> recent=readForegroundApps();
            runOnUiThread(()->{loading=false;if(isFinishing()||isDestroyed()||request!=generation)return;if(active){apps=loaded;foregroundApps=recent;lastData=data;render(data);}if(dirty&&active){dirty=false;handler.postDelayed(update,1200);}});
        });
    }
    private Card appCard(int i){
        Card c=new Card();c.key="app:"+apps.keyAt(i);c.title=apps.labelAt(i);c.artwork=apps.artworkAt(i);c.appIndex=i;
        String key=apps.keyAt(i);c.action=()->{try{ComponentName component=ComponentName.unflattenFromString(key);startActivity(new Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){Toast.makeText(this,"This app could not be opened.",Toast.LENGTH_SHORT).show();}};return c;
    }
    private List<Card> mediaCards(List<ShelfRepository.Item> items,String section){List<Card> cards=new ArrayList<>();for(ShelfRepository.Item item:items){Card c=new Card();c.key=section+":"+item.key;c.title=item.title;c.subtitle=item.subtitle;c.image=item.image;c.media=item;c.action=()->launch(item);cards.add(c);}return cards;}
    private Map<String,Long> readForegroundApps(){
        Map<String,Long> out=new HashMap<>();
        if(!controls.getBoolean("feature-recents",false))return out;
        try{long now=System.currentTimeMillis();UsageEvents events=((UsageStatsManager)getSystemService(USAGE_STATS_SERVICE)).queryEvents(now-7L*86400000,now);UsageEvents.Event event=new UsageEvents.Event();while(events.hasNextEvent()){events.getNextEvent(event);if(event.getEventType()==UsageEvents.Event.MOVE_TO_FOREGROUND)out.put(event.getPackageName(),event.getTimeStamp());}}catch(Exception ignored){}return out;
    }
    private Set<String> sources(String group){return new HashSet<>(controls.getStringSet(group+"-sources",new HashSet<>(ShelfRepository.APPS.keySet())));}
    private String sort(String group){return controls.getString(group+"-sort",group.equals("continue")?"recent":"balanced");}
    private void repaint(){handler.postDelayed(()->{if(active&&lastData!=null)render(lastData);},160);}
    private void render(ShelfRepository.Data data){
        String restore=focusedKey;int y=scroll.getScrollY();focusedArtwork=null;glass.setAlpha(0f);content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);content.removeAllViews();rows.clear();
        heroSpace=new FrameLayout(this);int height=Math.max(dp(120),scroll.getHeight()-dp(170));
        heroTitle=text("",48,WHITE);heroTitle.setTypeface(light);heroTitle.setSingleLine(true);heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams heroLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);heroLp.leftMargin=dp(12);heroLp.bottomMargin=dp(35);heroSpace.addView(heroTitle,heroLp);content.addView(heroSpace,new LinearLayout.LayoutParams(-1,height));
        List<Card> dock=new ArrayList<>(),other=new ArrayList<>();Set<String> pinned=new HashSet<>(),launchable=new HashSet<>();Map<String,Integer> appByPackage=new HashMap<>();
        for(int i=0;i<apps.appCount();i++){String pkg=apps.packageAt(i);if(pkg==null||apps.keyAt(i)==null||apps.labelAt(i).isEmpty())continue;appByPackage.put(pkg,i);if(i<PIN_COUNT){dock.add(appCard(i));pinned.add(pkg);}else other.add(appCard(i));if(!pkg.equals(getPackageName())&&!pkg.equals("com.android.tv.settings")&&!pkg.equals("com.android.vending"))launchable.add(pkg);}
        for(String pkg:ShelfFilters.recentApps(foregroundApps,pinned,launchable,2)){Card card=appCard(appByPackage.get(pkg));card.recent=true;card.subtitle="Recent";dock.add(card);}
        row(null,dock,true,null);
        List<ShelfRepository.Item> continuing=ShelfFilters.continuing(data.continuing,sources("continue"),sort("continue"),controls.getBoolean("continue-unstarted",false),controls.getBoolean("continue-dated",false));
        List<ShelfRepository.Item> recommendations=ShelfFilters.recommendations(data.recommendations,data.continuing,sources("recommend"),sort("recommend"),System.currentTimeMillis()/86400000);
        row("Continue watching",mediaCards(continuing,"continue"),false,"continue");
        row("Recommended for you",mediaCards(recommendations,"recommend"),false,"recommend");
        for(Map.Entry<String,List<ShelfRepository.Item>> entry:data.libraries.entrySet())if(!entry.getValue().isEmpty())row(entry.getKey(),mediaCards(entry.getValue(),entry.getKey()),false,null);
        if(!other.isEmpty())row("Apps",other,true,null);
        if(data.error!=null){TextView notice=text(data.error,14,MUTED);notice.setPadding(dp(6),dp(12),0,dp(12));content.addView(notice);if(checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED){TextView allow=button("Allow TV listings");allow.setOnClickListener(v->requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"},5));content.addView(allow);}}
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        boolean found=false;for(int r=0;r<rows.size();r++){Row row=rows.get(r);if(restore.equals("filter:"+row.group)&&row.filter!=null){row.filter.postDelayed(()->row.filter.requestFocus(),60);found=true;break;}for(int i=0;i<row.cards.size();i++)if(row.cards.get(i).key.equals(restore)){focus(r,i);found=true;break;}if(found)break;}
        if(!found&&!rows.isEmpty())focus(0,0);
        scroll.post(()->scroll.scrollTo(0,restore.isEmpty()?0:y));
        android.util.Log.i("MediaShelves","Pins="+pinned.size()+" recent="+(dock.size()-pinned.size())+" continue="+continuing.size()+" recommendations="+recommendations.size()+" libraries="+data.libraries.size());
    }
    private void row(String title,List<Card> cards,boolean appRow,String group){
        if(cards.isEmpty()&&group==null)return;
        LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);section.setClipChildren(false);
        Row row=new Row();row.cards=cards;row.group=group;int rowIndex=rows.size();rows.add(row);
        if(title!=null){
            LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(6),dp(12),dp(6),dp(12));
            TextView label=text(title,21,WHITE);label.setTypeface(medium);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(group!=null){TextView filter=button(filterLabel(group));filter.setTextSize(12);filter.setPadding(dp(14),dp(7),dp(14),dp(7));filter.setContentDescription(title+": Sort and filter");row.filter=filter;
                filter.setOnClickListener(v->filters(group));filter.setOnFocusChangeListener((v,yes)->{filter.setBackground(background(yes?0xffe7e7ef:0x24ffffff,18));filter.setTextColor(yes?0xff151518:WHITE);if(yes){focusedKey="filter:"+group;focusedArtwork=null;glass.setAlpha(0f);ensureVisible(v);}});
                filter.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(cards.isEmpty()){if(rowIndex+1<rows.size())focus(rowIndex+1,0);}else focus(rowIndex,0);return true;}if(key==KeyEvent.KEYCODE_DPAD_UP){if(rowIndex>0)focus(rowIndex-1,0);return true;}return false;});heading.addView(filter);
            }section.addView(heading);
        }
        row.list=new RecyclerView(this);row.list.setLayoutManager(new LinearLayoutManager(this,RecyclerView.HORIZONTAL,false));row.list.setItemAnimator(null);row.list.setOverScrollMode(View.OVER_SCROLL_NEVER);row.list.setClipToPadding(false);row.list.setClipChildren(false);row.list.setPadding(dp(6),dp(7),dp(8),dp(4));row.list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);row.list.setNestedScrollingEnabled(false);
        boolean dock=title==null;
        if(dock){GradientDrawable band=background(0xff141417,24);band.setStroke(dp(1),0x2bffffff);row.list.setBackground(band);row.list.setPadding(dp(12),dp(10),dp(12),dp(8));}
        int cardWidth=dock?Math.max(140,(int)((getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density-132)/PIN_COUNT)):appRow?156:126;
        row.list.setAdapter(new RecyclerView.Adapter<Holder>(){
            public int getItemViewType(int index){Card c=cards.get(index);return !appRow&&c.media!=null&&c.media.pkg.equals("com.google.android.youtube.tv")?1:0;}
            public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(appRow,cardWidth,dock,type==1);}
            public int getItemCount(){return cards.size();}
            public void onBindViewHolder(Holder holder,int index){
                Card card=cards.get(index);holder.bind(card);holder.itemView.setOnClickListener(v->card.action.run());
                holder.itemView.setOnLongClickListener(v->{if(card.appIndex>=0&&rowIndex==0&&!card.recent)editDock(index);else if(card.appIndex>=0)editDock(-1);else filters(group==null?"continue":group);return true;});
                holder.itemView.setOnFocusChangeListener((v,has)->{holder.focused(has);if(has){focusedKey=card.key;focusedArtwork=holder.frame;glowFor(card);if(rowIndex==0){scroll.scrollTo(0,0);}else ensureVisible(v);}});
                holder.itemView.setOnKeyListener((v,key,event)->{
                    if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int position=holder.getBindingAdapterPosition();if(position==RecyclerView.NO_POSITION)return false;
                    if(key==KeyEvent.KEYCODE_DPAD_UP){if(row.filter!=null){row.filter.requestFocus();}else if(rowIndex==0){scroll.scrollTo(0,0);settings.requestFocus();}else focus(rowIndex-1,position);return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(rowIndex+1<rows.size())focus(rowIndex+1,position);return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_LEFT&&position==0)return true;if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&position==cards.size()-1)return true;return false;
                });
            }
        });
        if(cards.isEmpty()){TextView empty=text("No items match your filters.",15,MUTED);empty.setPadding(dp(8),dp(16),0,dp(22));section.addView(empty);}else section.addView(row.list,new LinearLayout.LayoutParams(-1,dp(dock?104:appRow?132:258)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(dock?16:22);content.addView(section,lp);
    }
    private final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout frame;final ImageView art;final TextView title,caption,badge;final ProgressBar progress;final boolean app,dock;
        Holder(boolean isApp,int width,boolean inDock,boolean landscape){super(new LinearLayout(ShelfHomeActivity.this));app=isApp;dock=inDock;LinearLayout box=(LinearLayout)itemView;box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(6),dp(6),dp(6),dp(3));box.setFocusable(true);box.setClickable(true);box.setId(View.generateViewId());box.setLayoutParams(new RecyclerView.LayoutParams(dp(landscape?220:width),-1));
            frame=new FrameLayout(ShelfHomeActivity.this);frame.setBackground(background(0xff1c1c22,10));frame.setClipToOutline(true);frame.setTag("artwork");
            art=new ImageView(ShelfHomeActivity.this);art.setScaleType(app||landscape?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);frame.addView(art,new FrameLayout.LayoutParams(-1,-1));
            box.addView(frame,new LinearLayout.LayoutParams(-1,dp(app?79:166)));
            badge=text("RECENT",8,WHITE);badge.setTypeface(medium);badge.setLetterSpacing(.1f);badge.setPadding(dp(6),dp(3),dp(6),dp(3));badge.setBackground(background(0xcc22222a,6));FrameLayout.LayoutParams badgeLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);badgeLp.topMargin=dp(5);badgeLp.rightMargin=dp(5);frame.addView(badge,badgeLp);
            progress=new ProgressBar(ShelfHomeActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffc4b5ff));progress.setMax(1000);FrameLayout.LayoutParams pl=new FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM);frame.addView(progress,pl);
            title=text("",app?13:14,WHITE);title.setTypeface(medium);title.setMaxLines(app?1:2);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(3),dp(8),dp(3),0);box.addView(title);
            caption=text("",11,MUTED);caption.setSingleLine(true);caption.setEllipsize(TextUtils.TruncateAt.END);caption.setPadding(dp(3),dp(4),dp(3),0);box.addView(caption);
        }
        void bind(Card card){title.setText(card.title);title.setVisibility(dock?View.GONE:View.VISIBLE);caption.setText(card.subtitle==null?"":card.subtitle);caption.setVisibility(app?View.GONE:View.VISIBLE);badge.setVisibility(card.recent?View.VISIBLE:View.GONE);art.setTag(null);art.setImageDrawable(card.artwork);if(card.image!=null)images.bind(art,card.image);ShelfRepository.Item m=card.media;boolean show=m!=null&&m.position>0&&m.duration>0;progress.setVisibility(show?View.VISIBLE:View.GONE);if(show)progress.setProgress((int)Math.min(1000,m.position*1000/m.duration));itemView.setContentDescription(card.title+(card.recent?" · Recent app":card.subtitle==null?"":" · "+card.subtitle));focused(false);}
        void focused(boolean focus){itemView.setBackgroundColor(Color.TRANSPARENT);title.setTextColor(focus?Color.WHITE:WHITE);}
    }
    private void ensureVisible(View view){
        scroll.post(()->{if(!view.isAttachedToWindow())return;android.graphics.Rect rect=new android.graphics.Rect(0,0,view.getWidth(),view.getHeight());content.offsetDescendantRectToMyCoords(view,rect);int y=scroll.getScrollY(),h=scroll.getHeight();if(rect.bottom>y+h-dp(12))scroll.scrollTo(0,rect.bottom-h+dp(12));else if(rect.top<y+dp(8))scroll.scrollTo(0,Math.max(0,rect.top-dp(8)));});
    }
    private AlertDialog.Builder dialog(){return new AlertDialog.Builder(new ContextThemeWrapper(this,R.style.ShelfDialog));}
    private String filterLabel(String group){
        if(lastData==null)return "Sort & filter";Set<String> available=new HashSet<>();for(ShelfRepository.Item item:group.equals("continue")?lastData.continuing:lastData.recommendations)available.add(item.pkg);Set<String> selected=sources(group);selected.retainAll(available);if(selected.size()==available.size())return "Sort & filter";if(selected.size()==1)return ShelfRepository.APPS.get(selected.iterator().next())+" · Filter";return selected.size()+" apps · Filter";
    }
    private void filters(String group){
        focusedKey="filter:"+group;
        String title=group.equals("continue")?"Continue watching":"Recommendations";
        String[] choices=group.equals("continue")?new String[]{"Sort: "+sortLabel(group),"Apps","Next/new episodes: "+(controls.getBoolean("continue-unstarted",false)?"Shown":"Hidden"),"Undated items: "+(controls.getBoolean("continue-dated",false)?"Hidden":"Shown last"),"Reset filters"}:new String[]{"Sort: "+sortLabel(group),"Apps","Reset filters"};
        dialog().setTitle(title).setItems(choices,(d,index)->{
            if(index==0)chooseSort(group);else if(index==1)chooseSources(group);else if(group.equals("continue")&&index==2){controls.edit().putBoolean("continue-unstarted",!controls.getBoolean("continue-unstarted",false)).apply();repaint();}else if(group.equals("continue")&&index==3){controls.edit().putBoolean("continue-dated",!controls.getBoolean("continue-dated",false)).apply();repaint();}else{controls.edit().remove(group+"-sort").remove(group+"-sources").remove(group+"-unstarted").remove(group+"-dated").apply();repaint();}
        }).show();
    }
    private String sortLabel(String group){String value=sort(group);if(value.equals("progress"))return "Most progress";if(value.equals("app"))return "By app";if(value.equals("title"))return "A–Z";if(value.equals("mix"))return "Daily mix";return group.equals("continue")?"Recent activity":"Balanced by app";}
    private void chooseSort(String group){String[] keys=group.equals("continue")?new String[]{"recent","progress","app","title"}:new String[]{"balanced","mix","app","title"};String[] labels=group.equals("continue")?new String[]{"Recent activity · undated last","Most progress","By app","A–Z"}:new String[]{"Balanced by app","Daily mix","By app","A–Z"};int selected=Arrays.asList(keys).indexOf(sort(group));dialog().setTitle("Sort "+(group.equals("continue")?"Continue watching":"recommendations")).setSingleChoiceItems(labels,selected,(d,p)->{controls.edit().putString(group+"-sort",keys[p]).apply();d.dismiss();repaint();}).setNegativeButton("Cancel",null).show();}
    private void chooseSources(String group){
        List<ShelfRepository.Item> items=group.equals("continue")?lastData.continuing:lastData.recommendations;Set<String> available=new HashSet<>();for(ShelfRepository.Item item:items)available.add(item.pkg);
        List<String> keys=new ArrayList<>(),labels=new ArrayList<>();for(Map.Entry<String,String> app:ShelfRepository.APPS.entrySet())if(available.contains(app.getKey())){keys.add(app.getKey());labels.add(app.getValue());}
        Set<String> selected=sources(group);boolean[] checked=new boolean[keys.size()];for(int i=0;i<keys.size();i++)checked[i]=selected.contains(keys.get(i));
        dialog().setTitle("Show apps").setMultiChoiceItems(labels.toArray(new String[0]),checked,(d,p,on)->{if(on)selected.add(keys.get(p));else selected.remove(keys.get(p));}).setPositiveButton("Apply",(d,p)->{controls.edit().putStringSet(group+"-sources",new HashSet<>(selected)).apply();repaint();}).setNegativeButton("Cancel",null).show();
    }
    private void focus(int rowIndex,int position){
        if(rowIndex<0||rowIndex>=rows.size())return;Row row=rows.get(rowIndex);if(row.cards.isEmpty()){if(row.filter!=null)row.filter.requestFocus();return;}int target=Math.min(position,row.cards.size()-1);row.list.scrollToPosition(target);
        row.list.post(()->{RecyclerView.ViewHolder holder=row.list.findViewHolderForAdapterPosition(target);if(holder!=null)holder.itemView.requestFocus();else row.list.postDelayed(()->{RecyclerView.ViewHolder h=row.list.findViewHolderForAdapterPosition(target);if(h!=null)h.itemView.requestFocus();},60);});
    }
    private void launch(ShelfRepository.Item item){
        try{
            Intent intent=Intent.parseUri(item.intent,Intent.URI_INTENT_SCHEME);if(intent.getComponent()!=null&&!item.pkg.equals(intent.getComponent().getPackageName()))throw new SecurityException("Different package");
            String action=intent.getAction();if(action!=null&&!Intent.ACTION_VIEW.equals(action)&&!Intent.ACTION_MAIN.equals(action))throw new SecurityException("Not a content action");
            intent.setSelector(null);intent.setClipData(null);intent.setPackage(item.pkg);intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(intent);
        }catch(Exception e){Intent fallback=getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);if(fallback==null)fallback=getPackageManager().getLaunchIntentForPackage(item.pkg);if(fallback!=null)startActivity(fallback);else Toast.makeText(this,"This item is no longer available.",Toast.LENGTH_SHORT).show();}
    }
    private void menu(){dialog().setTitle("Home settings").setItems(new String[]{"Edit dock","Continue watching filters","Recommendation filters","Refresh rows","Launcher settings","Classic app layout","Optional features","TV settings"},(d,which)->{
        if(which==0)editDock(-1);else if(which==1)filters("continue");else if(which==2)filters("recommend");else if(which==3)refresh();else if(which==4)startActivity(new Intent(this,LauncherSettingsActivity.class));else if(which==5)startActivity(new Intent(this,MainActivity.class));else if(which==6)optionalFeatures();else startActivity(new Intent(Settings.ACTION_SETTINGS));
    }).show();}
    private void updatePhotoButton(){
        if(photos!=null)photos.setVisibility(controls.getBoolean("feature-photos",false)&&PhotoScreensaver.unavailable(this)==null?View.VISIBLE:View.GONE);
    }
    private void optionalFeatures(){
        String[] keys={"feature-photos","feature-recents","feature-artwork"};
        String[] labels={"Photos button","Recent apps","Download artwork"};
        String[] details={"Open Google’s screensaver on demand, then restore your idle selection. Requires optional secure-settings access, which can also change other protected settings. Personal albums stay managed by Google Home.","Read local foreground-app usage to show two recent apps. Nothing is uploaded. Requires Usage access.","Download posters from the documented seven HTTPS image hosts. Requests disclose your IP and artwork identifiers to those hosts. No account credentials are sent."};
        String[] rows=new String[keys.length];for(int i=0;i<rows.length;i++)rows[i]=labels[i]+": "+(controls.getBoolean(keys[i],false)?"On":"Off");
        dialog().setTitle("Optional features").setItems(rows,(d,index)->{
            boolean enabled=controls.getBoolean(keys[index],false);
            String extra="";
            if(index==0&&!enabled){String reason=PhotoScreensaver.unavailable(this);if(reason!=null){dialog().setTitle(labels[index]).setMessage(reason).setPositiveButton("OK",null).show();return;}}
            if(index==1&&!enabled)extra="\n\nIf no recent apps appear, enable Usage access for TV Shelves in TV settings. The README includes the optional ADB command.";
            dialog().setTitle(labels[index]).setMessage(details[index]+extra).setPositiveButton(enabled?"Turn off":"Enable",(confirm,which)->{
                controls.edit().putBoolean(keys[index],!enabled).apply();
                if(index==0){if(enabled)PhotoScreensaver.restore(this);updatePhotoButton();}
                if(index==2)images.setEnabled(!enabled);
                refresh();
            }).setNegativeButton("Cancel",null).show();
        }).show();
    }
    private void editDock(int selected){
        if(apps==null)return;if(selected<0){String[] names=new String[Math.min(PIN_COUNT,apps.appCount())];for(int i=0;i<names.length;i++)names[i]=(i+1)+" · "+apps.labelAt(i);dialog().setTitle("Choose a dock position").setItems(names,(d,p)->editDock(p)).show();return;}
        int n=apps.movableCount();List<Integer> indexes=new ArrayList<>();List<String> names=new ArrayList<>();for(int i=0;i<n;i++)if(!apps.labelAt(i).isEmpty()){indexes.add(i);names.add(apps.labelAt(i));}
        dialog().setTitle("Replace "+apps.labelAt(selected)).setItems(names.toArray(new String[0]),(d,p)->{
            List<String> keys=apps.movableKeys();String chosen=apps.keyAt(indexes.get(p));int from=keys.indexOf(chosen);if(selected>=keys.size()||from<0)return;Collections.swap(keys,selected,from);
            if(Order.save(Order.file(this),new Order(Order.dockKey(config.dockApps),keys,PIN_COUNT)))refresh();else Toast.makeText(this,"Could not save dock order.",Toast.LENGTH_SHORT).show();
        }).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==5)refresh();}
    @Override public void onBackPressed(){scroll.smoothScrollTo(0,0);if(!rows.isEmpty())focus(0,0);}
    public void onTileFocused(int position,View card){}public boolean onTileVerticalKey(int position,int keyCode){return false;}public void onLibraryRequested(){}public void onLibraryFocused(){}public void onTileLongPress(int p,View v){}public void onLibraryUp(){}
}
