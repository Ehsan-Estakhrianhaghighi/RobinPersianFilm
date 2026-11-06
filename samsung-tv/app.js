/* RobinPersianFilm Samsung Tizen TV frontend (no Android dependencies). */
(function () {
"use strict";
const el = id => document.getElementById(id);
const state = { tab:"home", sort:"newest", movies:[], series:[], item:null,
  sources:[], active:null, server:localStorage.getItem("robin_server") || "",
  favorites:JSON.parse(localStorage.getItem("robin_favorites") || "[]") };
const av = () => window.webapis && window.webapis.avplay;
const status = s => el("status").textContent=s;
const api = async route => {
  if (!state.server) throw Error("Set a companion server URL first");
  const res=await fetch(state.server.replace(/\/$/,"")+route);
  if(!res.ok)throw Error("Server error: "+res.status);
  return res.json();
};
function show(section){["listing","details","playback"].forEach(id=>el(id).hidden=id!==section);}
function focusFirst(section){const btn=el(section).querySelector("button");if(btn)btn.focus();}
function sorted(items){return items.slice().sort(state.sort==="az"?
 (a,b)=>a.title.localeCompare(b.title,undefined,{sensitivity:"base"}):
 (a,b)=>(b.publishedAt||0)-(a.publishedAt||0) || (a.rank||0)-(b.rank||0));}
function render(){
  const all=state.tab==="movies"?state.movies:state.tab==="series"?state.series:
   state.tab==="favorites"?state.movies.concat(state.series).filter(i=>state.favorites.includes(i.page)):
   state.movies.slice(0,18).concat(state.series.slice(0,18));
  el("heading").textContent=state.tab[0].toUpperCase()+state.tab.slice(1);
  el("sort").textContent="Sort: "+(state.sort==="az"?"A–Z":"Newest");
  document.querySelectorAll("[data-tab]").forEach(b=>b.classList.toggle("active",b.dataset.tab===state.tab));
  el("grid").replaceChildren();
  sorted(all).forEach(item=>{
    const b=document.createElement("button");b.className="card";
    const img=document.createElement("img");img.alt="";if(item.poster)img.src=item.poster;
    const title=document.createElement("span");title.textContent=item.title;b.append(img,title);
    b.onclick=()=>details(item);el("grid").append(b);
  });
  show("listing");const first=el("grid").querySelector("button");(first||el("sort")).focus();
}
async function load(){
 status("Loading catalog…");
 try{const data=await api("/api/catalog");state.movies=data.movies||[];state.series=data.series||[];
 status("Catalog loaded");render();}catch(e){status(e.message);if(!state.server)settings();}
}
async function details(item){
  state.item=item;show("details");el("detail-content").replaceChildren();
  const row=document.createElement("div");row.className="detail-layout";
  const img=document.createElement("img");img.alt="";if(item.poster)img.src=item.poster;
  const info=document.createElement("div");const heading=document.createElement("h2");
  heading.textContent=item.title;info.append(heading);
  const favorite=document.createElement("button");
  function updateFavorite(){favorite.textContent=(state.favorites.includes(item.page)?"★ Remove":"☆ Add")+" favorite";}
  updateFavorite();favorite.onclick=()=>{
    state.favorites=state.favorites.includes(item.page)?
      state.favorites.filter(x=>x!==item.page):state.favorites.concat(item.page);
    localStorage.setItem("robin_favorites",JSON.stringify(state.favorites));updateFavorite();};
  info.append(favorite);row.append(img,info);el("detail-content").append(row);
  if(!item.series){
    const b=document.createElement("button");b.textContent="▶ Play movie";
    b.onclick=()=>resolve(item.page,item.title);info.append(b);
  }else{
    const episodes=document.createElement("div");episodes.id="episode-grid";info.append(episodes);
    status("Loading episodes…");
    try{const data=await api("/api/episodes?url="+encodeURIComponent(item.page));
      (data.episodes||[]).forEach(ep=>{
        const b=document.createElement("button");b.textContent=ep.name||"Episode";
        b.onclick=()=>resolve(ep.page,item.title+" • "+b.textContent);episodes.append(b);
      });status("Choose an episode");
    }catch(e){status(e.message);}
  }
  el("back-details").focus();
}
const video=document.createElement("video");video.controls=true;
function stop(){
  const player=av();if(player)try{if(player.getState()!=="NONE"){player.stop();player.close();}}catch(e){}
  video.pause();video.removeAttribute("src");video.load();
  state.active=null;el("video-surface").replaceChildren();
}
function getPosition(){try{return av()?av().getCurrentTime():Math.round(video.currentTime*1000);}catch(e){return 0;}}
function play(source,position){
  const player=av();state.active=source;
  el("quality").querySelectorAll("button").forEach(b=>b.classList.toggle("selected",b.dataset.url===source.url));
  status("Loading "+source.quality+"p…");
  if(player){
    try{
      if(player.getState()!=="NONE"){player.stop();player.close();}
      player.open(source.url);
      player.setDisplayRect(0,190,1920,780);
      player.setListener({onbufferingstart:()=>status("Buffering…"),onbufferingcomplete:()=>status("Playing"),
        onerror:e=>status("Playback error: "+e)});
      player.prepareAsync(()=>{
        const start=()=>{player.play();status("Playing "+source.quality+"p");};
        if(position>0)player.seekTo(position,start,start);else start();
      },e=>status("Cannot open video: "+e));
    }catch(e){status(e.message);}
  }else{
    if(!video.parentNode)el("video-surface").append(video);
    video.src=source.url;video.addEventListener("loadedmetadata",()=>{
      if(position>0)video.currentTime=position/1000;
    },{once:true});video.play().catch(e=>status(e.message));
  }
}
async function resolve(url,title){
  show("playback");el("player-title").textContent=title;el("quality").replaceChildren();
  status("Resolving stream…");
  try{
    const result=await api("/api/resolve?url="+encodeURIComponent(url));
    state.sources=result.sources||[];
    if(!state.sources.length)throw Error("No stream available");
    state.sources.forEach(source=>{
      const b=document.createElement("button");b.dataset.url=source.url;b.textContent=source.quality+"p";
      b.onclick=()=>play(source,getPosition());el("quality").append(b);
    });
    play(state.sources.find(s=>s.quality==="720")||state.sources[0],0);
    el("quality").querySelector("button").focus();
  }catch(e){status(e.message);el("back-player").focus();}
}
function playPause(){
  try{if(av()){const st=av().getState();if(st==="PLAYING")av().pause();else if(st==="PAUSED")av().play();}
  else if(video.paused)video.play();else video.pause();}catch(e){status(e.message);}
}
function settings(){el("server-url").value=state.server;el("server-dialog").showModal();el("server-url").focus();}
el("settings").onclick=settings;
el("save-server").onclick=()=>{
 const value=el("server-url").value.trim().replace(/\/$/,"");
 if(!/^https?:\/\//.test(value)){status("Enter a valid HTTP or HTTPS URL");return;}
 state.server=value;localStorage.setItem("robin_server",value);setTimeout(load,50);
};
el("sort").onclick=()=>{state.sort=state.sort==="az"?"newest":"az";render();};
document.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{state.tab=b.dataset.tab;render();});
el("back-details").onclick=render;
el("back-player").onclick=()=>{stop();if(state.item)details(state.item);else render();};
el("toggle-play").onclick=playPause;
el("fullscreen").onclick=()=>{
  if(!av()&&el("video-surface").requestFullscreen)el("video-surface").requestFullscreen();
  else status("Video is displayed using Samsung AVPlay");
};
window.addEventListener("keydown",event=>{
 if(el("server-dialog").open)return;
 const key=event.key;
 if(["Escape","Backspace","BrowserBack"].includes(key)||event.keyCode===10009){
   event.preventDefault();if(!el("playback").hidden)el("back-player").click();
   else if(!el("details").hidden)render();else try{tizen.application.getCurrentApplication().exit();}catch(e){}
   return;
 }
 if(!el("playback").hidden){
   if(["MediaPlayPause","MediaPlay","MediaPause"].includes(key)){event.preventDefault();playPause();return;}
   if(["MediaRewind","MediaFastForward"].includes(key)){event.preventDefault();
     const ms=Math.max(0,getPosition()+(key==="MediaRewind"?-10000:15000));
     try{if(av())av().seekTo(ms);else video.currentTime=ms/1000;}catch(e){}return;
   }
 }
 const direction={ArrowUp:[0,-1],ArrowDown:[0,1],ArrowLeft:[-1,0],ArrowRight:[1,0]}[key];
 if(!direction)return;
 const focused=document.activeElement;if(!focused||!focused.getBoundingClientRect)return;
 const box=focused.getBoundingClientRect(),cx=box.left+box.width/2,cy=box.top+box.height/2;
 let choice=null,best=Infinity;const [dx,dy]=direction;
 document.querySelectorAll("button").forEach(button=>{
   if(button===focused||button.closest("[hidden]"))return;
   const r=button.getBoundingClientRect();if(!r.width||!r.height)return;
   const x=r.left+r.width/2-cx,y=r.top+r.height/2-cy;
   const along=x*dx+y*dy;if(along<5)return;
   const cross=Math.abs(x*dy-y*dx),cost=along+cross*3;
   if(cost<best){best=cost;choice=button;}
 });if(choice){event.preventDefault();choice.focus();}
});
try{if(window.tizen&&tizen.tvinputdevice){
 ["MediaPlayPause","MediaPlay","MediaPause","MediaFastForward","MediaRewind"].forEach(key=>{
   try{tizen.tvinputdevice.registerKey(key);}catch(e){}
 });}}catch(e){}
render();if(state.server)load();else settings();
})();