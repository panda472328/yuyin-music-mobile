package com.yuyin.music.mobile;

import org.json.JSONObject;

/** Reads the original site's HTML5 media; no stream URL extraction and no JavascriptInterface. */
final class MediaScript {
    private MediaScript() { }
    static String build(String command, String bvid, double volume, double value) {
        String args = "{command:" + JSONObject.quote(command) + ",bvid:" + JSONObject.quote(bvid) +
                ",volume:" + volume + ",value:" + value + "}";
        return "(function(){const a=" + args + ";" +
                "const empty={found:false,paused:true,ended:false,currentTime:0,duration:0,readyState:0,playing:false,mediaError:0};" +
                "const path='/video/'+a.bvid;if((location.pathname!==path&&location.pathname!==path+'/')||new URLSearchParams(location.search).getAll('p').some(function(p){return p!=='1';}))return JSON.stringify(Object.assign(empty,{navigationMismatch:true}));" +
                "const key='__yuyinNativeMedia';const m=window[key]||(window[key]={video:null,ended:false,error:0,volume:a.volume,playing:false,lastTime:0});" +
                "if(!m.captureInstalled){window.addEventListener('ended',function(e){if(e.target!==m.video)return;m.ended=true;m.playing=false;m.video.pause();m.video.muted=true;e.stopImmediatePropagation();},true);m.captureInstalled=true;}" +
                "const v=document.querySelector('.bpx-player-video-wrap video')||document.querySelector('.bilibili-player-video video')||document.querySelector('video');" +
                "if(!v){const t=(document.body?document.body.innerText:'').slice(0,14000);const reasons=['412 Precondition Failed','403 Forbidden','安全验证','人机验证','访问异常','访问受限','请求被拦截','登录后观看','视频已失效','视频已删除','视频不见了','应版权方要求','所在地区不可用'];return JSON.stringify(Object.assign(empty,{blocked:reasons.find(function(r){return t.includes(r);})||null}));}" +
                "if(m.video!==v){m.video=v;m.ended=false;m.error=0;m.playing=false;m.playError=null;m.lastTime=v.currentTime;v.volume=m.volume;v.addEventListener('error',function(){if(m.video===v)m.error=v.error?v.error.code:0;});v.addEventListener('playing',function(){if(m.video===v)m.playing=true;});['pause','waiting','stalled','ended'].forEach(function(n){v.addEventListener(n,function(){if(m.video===v)m.playing=false;});});}" +
                "if(a.command==='volume'){m.volume=a.value;v.volume=a.value;}" +
                "if(a.command==='pause'){v.pause();m.playing=false;}" +
                "if(a.command==='seek'){v.currentTime=Math.max(0,Math.min(a.value,Number.isFinite(v.duration)?v.duration:a.value));m.ended=false;}" +
                "if(a.command==='play'){m.ended=false;m.volume=a.volume;m.playError=null;v.volume=a.volume;v.muted=false;try{const p=v.play();if(p&&p.then)p.then(function(){if(m.video===v)m.playing=!v.paused;}).catch(function(e){if(m.video===v)m.playError=e&&e.message?e.message:String(e);});}catch(e){m.playError=e.message||String(e);}}" +
                "if(!v.paused&&v.currentTime>m.lastTime+0.01)m.playing=true;m.lastTime=v.currentTime;" +
                "return JSON.stringify({found:true,paused:v.paused,ended:m.ended||v.ended,currentTime:Number.isFinite(v.currentTime)?v.currentTime:0,duration:Number.isFinite(v.duration)?v.duration:0,readyState:v.readyState,playing:m.playing,mediaError:v.error?v.error.code:m.error,playError:m.playError||null});})()";
    }
}
