package com.yuyin.music.mobile;

/** Scoped to the selected source document; native/app seeks and trusted gestures may take control. */
final class MediaTimeline {
    static String install() {
        return "if(!m.timeline){m.timeline={time:0,wall:performance.now(),playing:false,initialized:false,intent:0};" +
            "['pointerdown','keydown'].forEach(function(type){document.addEventListener(type,function(event){if(event.isTrusted)m.timeline.intent=performance.now()+1500;},true);});}";
    }
    static String guard() {
        return "const t=m.timeline;const now=performance.now();const position=Number.isFinite(v.currentTime)?v.currentTime:0;" +
            "if(a.command==='seek'){t.time=Math.max(0,a.value);t.initialized=true;}" +
            "else if(v.readyState>=1){const delta=Math.max(0,(now-t.wall)/1000);const rate=Number.isFinite(v.playbackRate)?Math.min(16,Math.max(.1,v.playbackRate)):1;" +
            "const allowed=t.playing?delta*rate+1:1;const intentional=t.initialized&&now<t.intent;" +
            "if(!t.initialized||!intentional&&(position>t.time+allowed||position<t.time-1)){try{v.currentTime=t.time;m.ended=false;}catch(e){}}" +
            "else t.time=position;t.initialized=true;}t.wall=now;t.playing=!v.paused&&v.readyState>=2;";
    }
    private MediaTimeline() { }
}
