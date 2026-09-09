package com.idevicerestore.preview;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  private WebView web;

  private static final String FIX_SCRIPT =
      "(function(){" +
      "var c=document.getElementById('screen'),a=document.getElementById('atlas');" +
      "if(!c||!a)return;" +
      "var ctx=c.getContext('2d');" +
      "var crops={home:[24,126,279,720],firmware:[324,126,286,720],restore:[628,126,282,720],diag:[928,126,283,720],settings:[1232,126,283,720]};" +
      "var order=['home','firmware','restore','diag','settings'];var current='home';" +
      "function draw(){var p=crops[current];c.width=p[2];c.height=p[3];ctx.clearRect(0,0,c.width,c.height);ctx.drawImage(a,p[0],p[1],p[2],p[3],0,0,p[2],p[3]);}" +
      "function go(id){if(!crops[id])return;current=id;draw();}" +
      "function hit(e){var r=c.getBoundingClientRect(),x=(e.clientX-r.left)*c.width/r.width,y=(e.clientY-r.top)*c.height/r.height,w=c.width,h=c.height;" +
      "if(y>h*.89){var n=Math.max(0,Math.min(4,Math.floor(x/(w/5))));go(order[n]);return;}" +
      "if(current==='home'){if(x>w*.82&&y<h*.15){go('settings');return;}if(y>h*.57&&y<h*.75){go(x<w*.5?'restore':'firmware');return;}if(y>h*.74&&y<h*.9){go('diag');return;}}" +
      "if((current==='restore'||current==='diag'||current==='settings')&&x<w*.18&&y<h*.14){go('home');return;}" +
      "}" +
      "c.onclick=hit;" +
      "var sx=0,sy=0;c.ontouchstart=function(e){var t=e.touches[0];sx=t.clientX;sy=t.clientY;};c.ontouchend=function(e){var t=e.changedTouches[0],dx=t.clientX-sx,dy=t.clientY-sy;if(Math.abs(dx)>60&&Math.abs(dx)>Math.abs(dy)*1.3){var i=order.indexOf(current);i=dx<0?Math.min(4,i+1):Math.max(0,i-1);go(order[i]);}};" +
      "function ready(){if(a.complete&&a.naturalWidth){draw();}else{a.onload=draw;}}ready();" +
      "})();";

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(true);
    settings.setBuiltInZoomControls(false);
    settings.setDisplayZoomControls(false);
    web.setVerticalScrollBarEnabled(false);
    web.setHorizontalScrollBarEnabled(false);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setBackgroundColor(0xFFEEF2F7);
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        view.evaluateJavascript(FIX_SCRIPT, null);
      }
    });
    web.loadUrl("file:///android_asset/index.html");
    setContentView(web);
    hideSystemBars();
  }

  private void hideSystemBars() {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else {
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN |
          View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
          View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
          View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
  }

  @Override public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) hideSystemBars();
  }
}
