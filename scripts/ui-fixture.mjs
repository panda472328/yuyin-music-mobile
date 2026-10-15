export const fixture = `
export const isAndroid = true;
export const getLyricOffset = (prefs,provider,bvid) => prefs.lyricOffsets[provider+':'+bvid] ?? (provider==='bilibili' ? -.25 : 0);
const song = {id:'BV0TEST00001',bvid:'BV0TEST00001',title:'测试歌曲 A',artist:'测试歌手',cover:'',duration:260,playCount:6250000,source:'bilibili',url:'https://www.bilibili.com/video/BV0TEST00001/',searchQuery:'测试歌曲 A'};
const other = {...song,id:'BV0TEST00002',bvid:'BV0TEST00002',title:'测试歌曲 B',duration:320};
const defaultLibrary=()=>({version:1,favorites:[],playlists:[{id:'playlist-default',name:'我的歌单',description:'',createdAt:Date.now(),songs:[]}],history:[],settings:{volume:.7,playMode:'sequence',autoPlayFirst:false},queue:[]});
let status={state:'idle',song:null,currentTime:0,duration:0,volume:.7,error:null};
const statusListeners=new Set(),sessionListeners=new Set(),endedListeners=new Set();
let updateState={state:'idle',currentVersion:'0.1.2',currentVersionCode:3,revision:0};
const updateListeners=new Set();
const emitUpdate=next=>{updateState={...next,revision:updateState.revision+1};for(const cb of updateListeners)cb({...updateState})};
const emit=()=>{for(const cb of statusListeners)cb({...status})};
window.__mobileQA={playCalls:0,seekCalls:[],saveCalls:0,failSave:false,advance:(seconds)=>{status.currentTime=seconds;emit()},ended:()=>{status.state='ended';emit();for(const cb of endedListeners)cb(status.song)}};
window.__mobileQA.updateCalls=0;window.__mobileQA.downloadCalls=0;window.__mobileQA.installCalls=0;
window.__mobileQA.offerUpdate=()=>emitUpdate({state:'available',currentVersion:'0.1.2',currentVersionCode:3,update:{version:'0.1.3',versionCode:4,url:'https://github.com/panda472328/yuyin-music-mobile/releases/download/android-v0.1.3/Yuyin-Mobile-0.1.3.apk',releaseNotesUrl:'https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.3',notes:'受控更新说明：保留收藏和歌单。',size:8000000,publishedAt:'2026-10-08T00:00:00.000Z'}});
window.__mobileQA.updateReady=()=>emitUpdate({...updateState,state:'ready',progress:100});
window.__mobileQA.staleUpdate=()=>{for(const cb of updateListeners)cb({state:'idle',currentVersion:'0.1.2',currentVersionCode:3,revision:0})};
const listener=(set,callback)=>{set.add(callback);return Promise.resolve({remove:async()=>set.delete(callback)})};
const fixtureComment={id:'11',rootId:'0',parentId:'0',author:{mid:1234,username:'评论测试用户',avatar:''},message:'这是一条虚构评论',createdAt:1700000000,likeCount:2,replyCount:0,liked:false,replies:[]};
const postedComments=[];
let floating=JSON.parse(localStorage.getItem('qa-floating')||'null')||{enabled:false,locked:false,style:"classic",font:"sans",fontSize:24,color:"white",opacity:1,x:.5,y:.2,permission:true};let sleepUntil=null;
const storeListeners=new Set();const storeEvent=key=>{for(const cb of storeListeners)cb(key)};
const nativePlay=selected=>{const lib=JSON.parse(localStorage.getItem('qa-mobile-library')||JSON.stringify(defaultLibrary()));lib.history=[{song:selected,playedAt:Date.now()},...lib.history.filter(item=>item.song.bvid!==selected.bvid)].slice(0,100);localStorage.setItem('qa-mobile-library',JSON.stringify(lib));status={...status,state:'playing',song:selected,currentTime:0,duration:selected.duration};storeEvent('background_history');emit();return {...status}};
const nativeSkip=direction=>{const lib=JSON.parse(localStorage.getItem('qa-mobile-library')||JSON.stringify(defaultLibrary()));const at=lib.queue.findIndex(s=>s.bvid===status.song?.bvid);const next=lib.queue[(at+direction+lib.queue.length)%lib.queue.length];return next?nativePlay(next):{...status}};
window.__mobileQA.ended=()=>{status.state='ended';emit();for(const cb of endedListeners)cb(status.song);nativeSkip(1)};
export const mobile={
getSleepTimer:async()=>({until:sleepUntil}),setSleepTimer:async until=>{sleepUntil=until;return {until}},
getFloatingLyrics:async()=>({...floating}),setFloatingLyrics:async settings=>{if(window.__mobileQA.failSave)throw Error("测试保存失败");floating={...settings};localStorage.setItem('qa-floating',JSON.stringify(floating));storeEvent('floating_lyrics');return {...floating}},requestOverlayPermission:async()=>({granted:true}),syncFloatingLyrics:async(bvid,provider,lines)=>{window.__mobileQA.floatingSync={bvid,provider,lines}},addStoreListener:cb=>listener(storeListeners,cb),
exportBackup:async data=>{window.__mobileQA.backup=data;return {saved:true}},importBackup:async()=>({data:window.__mobileQA.importData??null}),restoreBackup:async(library,preferences,styles)=>{if(window.__mobileQA.failSave)throw Error("测试保存失败");localStorage.setItem("qa-mobile-library",JSON.stringify(library));localStorage.removeItem('qa-corrupt-library');if(preferences)localStorage.setItem("qa-mobile-preferences",JSON.stringify(preferences));if(styles){floating={...styles,permission:true};localStorage.setItem('qa-floating',JSON.stringify(floating));storeEvent('floating_lyrics')}return library},
skip:async direction=>nativeSkip(direction),
setAppearance:async(_theme,dark)=>({dark}),addAppearanceListener:()=>Promise.resolve({remove:async()=>{}}),
searchSuggestions:async(query)=>[query+' 原曲',query+' 现场'],
comments:{list:async(bvid,sort,page)=>({bvid,oid:'123',sort,page,pageSize:20,total:1+postedComments.length,hasMore:false,closed:false,comments:[...postedComments,fixtureComment]}),replies:async(bvid,rootId,page)=>({bvid,rootId,page,pageSize:20,total:0,hasMore:false,comments:[]}),like:async(_bvid,id,liked)=>({id,liked}),create:async(request)=>{const comment={...fixtureComment,id:String(12+postedComments.length),message:request.message};postedComments.push(comment);return {comment,pendingReview:false}}},
getUpdateState:async()=>({...updateState}),addUpdateListener:cb=>listener(updateListeners,cb),
checkUpdate:async()=>{window.__mobileQA.updateCalls++;emitUpdate({...updateState,state:'upToDate'});return {...updateState}},
downloadUpdate:async()=>{window.__mobileQA.downloadCalls++;emitUpdate({...updateState,state:'downloading',progress:42});return {...updateState}},
cancelUpdate:async()=>{emitUpdate({...updateState,state:'available',progress:0});return {...updateState}},
installUpdate:async()=>{window.__mobileQA.installCalls++;emitUpdate({...updateState,state:'permissionRequired'});return {...updateState}},
checkAccount:async()=>localStorage.getItem('qa-mobile-login')==='1'?{loggedIn:true,account:{mid:1234,username:'测试用户',avatar:''}}:{loggedIn:false,account:null},
login:async()=>{localStorage.setItem('qa-mobile-login','1')},openSource:async()=>{},
search:async(query,page=1,mode='song')=>{window.__mobileQA.searchModes=(window.__mobileQA.searchModes||[]).concat(mode);if(window.__mobileQA.holdNextSearch){window.__mobileQA.holdNextSearch=false;return new Promise(resolve=>{window.__mobileQA.releaseStaleSearch=()=>resolve({query,songs:[{...song,title:'过期搜索结果'}],page,pageSize:20,total:1,hasMore:false})})}return ({query,songs:[song,other],page,pageSize:20,total:2,hasMore:false})},
play:async(selected)=>{window.__mobileQA.playCalls++;return nativePlay(selected)},
pause:async()=>{status.state='paused';emit();return {...status}},resume:async()=>{status.state='playing';emit();return {...status}},
seek:async(seconds)=>{window.__mobileQA.seekCalls.push(seconds);status.currentTime=seconds;emit();return {...status}},
setVolume:async(volume)=>{status.volume=volume;emit();return {...status}},getStatus:async()=>({...status}),
getLyrics:async(selected,provider)=>({provider,query:'测试歌曲 A',match:{id:1,trackName:'测试歌曲 A',artistName:'测试歌手',albumName:'测试专辑',duration:260,instrumental:false,syncedLyrics:'[00:02.00]测试歌词第一句\\n[00:07.00]测试歌词第二句\\n[00:12.00]测试歌词第三句\\n[00:17.00]测试歌词第四句',plainLyrics:null},...(provider==='bilibili'?{subtitle:{label:'中文',language:'zh-CN',isAI:true}}:{})}),
loadLibrary:async()=>{if(localStorage.getItem('qa-corrupt-library'))throw Error('测试歌库不可读，原记录已保留');return JSON.parse(localStorage.getItem('qa-mobile-library')||'null')||defaultLibrary()},
saveLibrary:async(state)=>{await new Promise(resolve=>setTimeout(resolve,30));if(window.__mobileQA.failSave)throw new Error('QA: 存储空间不足');window.__mobileQA.saveCalls++;localStorage.setItem('qa-mobile-library',JSON.stringify(state));return state},
loadPreferences:async()=>JSON.parse(localStorage.getItem('qa-mobile-preferences')||'null')||{version:1,lyricsProvider:'bilibili',lyricOffsets:{}},
savePreferences:async(state)=>{if(window.__mobileQA.failSave)throw new Error('QA: 设置存储失败');localStorage.setItem('qa-mobile-preferences',JSON.stringify(state))},
addStatusListener:cb=>listener(statusListeners,cb),addEndedListener:cb=>listener(endedListeners,cb),addSessionListener:cb=>listener(sessionListeners,cb),
getFavoriteFolders:async()=>({mid:1234,username:'测试用户',account:{mid:1234,name:'测试用户'},folders:[{id:42,title:'手机收藏夹',mediaCount:2,cover:'',description:'',isDefault:false}]}),
getFavoriteSongs:async()=>({folder:{id:42,title:'手机收藏夹',mediaCount:2,cover:'',description:'',isDefault:false},songs:[song,other],skippedCount:0,total:2})
};`
