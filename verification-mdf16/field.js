const fs=require('fs'),path=require('path'),assert=require('assert'),{spawnSync}=require('child_process');
const mineflayer=require('C:/Users/gusdl/OneDrive/Desktop/Dalmaru/tools/autoplay/node_modules/mineflayer');
const root=__dirname,bots=[],allEvents=[];let request=BigInt(Date.now());
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const vlong=value=>{let v=BigInt.asUintN(64,BigInt(value)),a=[];do{let b=Number(v&127n);v>>=7n;if(v)b|=128;a.push(b);}while(v);return Buffer.from(a);};
const vint=value=>vlong(value),str=value=>{const b=Buffer.from(value);return Buffer.concat([vint(b.length),b]);};
const uparts=u=>{const h=u.replace(/-/g,'');return[vlong(BigInt('0x'+h.slice(0,16))),vlong(BigInt('0x'+h.slice(16)))];};
const fixed=u=>Buffer.from(u.replace(/-/g,''),'hex');
class Reader{constructor(b){this.b=b;this.i=0;}vl(){let n=0n,s=0n,b;do{b=this.b[this.i++];n|=BigInt(b&127)<<s;s+=7n;}while(b&128);return BigInt.asIntN(64,n);}vi(){return Number(this.vl());}bool(){return this.b[this.i++]!==0;}str(){let n=this.vi(),s=this.b.toString('utf8',this.i,this.i+n);this.i+=n;return s;}uuid(){let a=BigInt.asUintN(64,this.vl()).toString(16).padStart(16,'0'),b=BigInt.asUintN(64,this.vl()).toString(16).padStart(16,'0');return format(a+b);}fixed(){let h=this.b.subarray(this.i,this.i+16).toString('hex');this.i+=16;return format(h);}}
function format(h){return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;}
function decode(name,b){const r=new Reader(b);switch(name){
case 'welcome':return{protocol:r.vi()};case 'character_selection_ready':return{ready:r.bool()};
case 'character_create_response':return{request:r.vl().toString(),id:r.uuid(),slot:r.vi(),name:r.str(),accepted:r.bool(),replayed:r.bool(),status:r.str()};
case 'character_select_response':return{request:r.vl().toString(),id:r.uuid(),accepted:r.bool(),replayed:r.bool(),status:r.str()};
case 'run_response':return{request:r.vl().toString(),accepted:r.bool(),id:r.uuid(),size:r.vi(),tier:r.vi(),state:r.str(),status:r.str()};
case 'loadout_response':{let request=r.vl().toString(),id=r.fixed(),revision=r.vl().toString(),slots=Array.from({length:5},()=>r.str());return{request,id,revision,slots,editable:r.bool(),status:r.str()};}
case 'combat_state_response':{let stamina=r.vi(),max=r.vi(),inCombat=r.bool(),mode=r.bool(),remaining=r.vi(),cooldowns=[],slots=[];for(let i=0;i<5;i++){cooldowns.push(r.vi());slots.push(r.str());}return{stamina,max,inCombat,mode,remaining,cooldowns,slots,dodge:r.vi(),guard:r.vi()};}
case 'character_profile_response':{let id=r.uuid(),level=r.vi();return{id,level};}
default:return{};}}
function log(s){console.log(s);fs.appendFileSync(path.join(root,'packet.log'),s+'\n');}
function sql(text){const p=spawnSync('C:/Program Files/PostgreSQL/18/bin/psql.exe',['-h','127.0.0.1','-p','57700','-U','oscar_fixture','-d','oscar_fixture','-v','ON_ERROR_STOP=1','-At','-c',text],{encoding:'utf8',windowsHide:true});if(p.status!==0)throw new Error(p.stderr);return p.stdout.trim();}
function cmd(text){fs.appendFileSync(path.join(root,'server.cmd'),text+'\n');}
async function wait(fn,label,timeout=12000){const start=Date.now();while(Date.now()-start<timeout){const r=fn();if(r)return r;await pause(50);}throw new Error('Timeout '+label);}
function send(b,channel,...data){b._client.write('custom_payload',{channel:'dalmaru:'+channel,data:Buffer.concat(data)});}
async function connect(name){const b=mineflayer.createBot({host:'127.0.0.1',port:57702,username:name,auth:'offline',version:'26.1'});bots.push(b);b.events=[];
b._client.on('custom_payload',p=>{if(!p.channel.startsWith('dalmaru:'))return;try{const n=p.channel.slice(8),body=decode(n,p.data),e={name:n,body,time:Date.now()};b.events.push(e);allEvents.push({bot:name,...e});if(n==='combat_state_response')b.combat=body;else{log(name+' '+n+' '+JSON.stringify(body));if(n==='loadout_response')b.slots=body;if(n==='character_create_response'&&body.accepted)b.character=body.id;}}catch(error){log('DECODE_FAIL '+error.stack);}});
b.once('login',()=>send(b,'hello',vint(28)));b.on('kicked',r=>log(name+' KICKED '+JSON.stringify(r)));b.on('error',e=>log(name+' ERROR '+e.message));b.on('death',()=>{b.deathAt=Date.now();log(name+' DEATH');});b.on('spawn',()=>log(name+' SPAWN'));b.on('health',()=>log(name+' HEALTH '+b.health));
await wait(()=>b.events.find(e=>e.name==='welcome'&&e.body.protocol===28),'protocol28 '+name);await wait(()=>b.events.find(e=>e.name==='character_selection_ready'&&e.body.ready),'selection ready '+name);return b;}
async function create(b,i){const req=(++request).toString();send(b,'character_create_request',vlong(req),vint(1),str('qa16_'+i+'_'+Date.now().toString().slice(-6)));let c=await wait(()=>b.events.find(e=>e.name==='character_create_response'&&e.body.request===req),'create');assert(c.body.accepted,c.body.status);const sel=(++request).toString();send(b,'character_select_request',vlong(sel),...uparts(c.body.id));await wait(()=>b.events.find(e=>e.name==='character_select_response'&&e.body.request===sel&&e.body.accepted),'select');return c.body.id;}
async function update(b,slots){let req=(++request).toString();send(b,'loadout_update',vlong(req),fixed(b.character),vlong(b.slots.revision),...slots.map(str));return(await wait(()=>b.events.find(e=>e.name==='loadout_response'&&e.body.request===req),'loadout update')).body;}
function ready(b,id){send(b,'run_ready_request',...uparts(id),Buffer.from([1]));}
async function main(){
cmd('difficulty peaceful');await pause(500);const b=await connect('Q16C_0');const character='133e0ea0-3d6d-4f98-9af8-32b073e47ccb';b.character=character;const sel=(++request).toString();send(b,'character_select_request',vlong(sel),...uparts(character));await wait(()=>b.events.find(e=>e.name==='character_select_response'&&e.body.request===sel&&e.body.accepted),'selection');
sql("UPDATE dalmaru.town_safe_zones SET x=15,z=0,radius=8 WHERE name='oscar_qa16'");cmd('tp Q16C_0 15.5 101 0.5');send(b,'loadout_request',vlong(++request));await pause(1800);assert.equal(Number(sql(`SELECT x FROM dalmaru.character_safe_points WHERE character_id='${character}'`)),15.5);
cmd('effect give Q16C_0 minecraft:instant_health 1 10');await pause(500);const maximum=b.health;assert(maximum>0);cmd('setblock 0 100 0 minecraft:stone');cmd('tp Q16C_0 -20.5 101 0.5');await pause(500);send(b,'combat_mode_request',Buffer.from([1]));await wait(()=>b.combat&&b.combat.mode,'manual mode');
cmd('kill Q16C_0');await wait(()=>b.deathAt,'field death');const timer=setInterval(()=>{log('FIELD_POS '+JSON.stringify(b.entity.position));cmd('data get entity Q16C_0 Pos');},500);try{await wait(()=>b.entity&&Math.abs(b.entity.position.x-15.5)<.2&&Math.abs(b.entity.position.y-101)<.2,'checkpoint return',7000);}finally{clearInterval(timer);};const elapsed=Date.now()-b.deathAt;
assert(elapsed>=2500&&elapsed<6500,'Field return timing wrong '+elapsed);await wait(()=>b.health>=maximum*.45&&b.health<=maximum*.6,'field health reset');const restoredHealth=b.health;await wait(()=>b.combat&&!b.combat.mode&&b.combat.stamina>=b.combat.max*.5&&b.combat.stamina<=b.combat.max*.7,'resource and mode reset');assert(b.combat.stamina>=b.combat.max*.5&&b.combat.stamina<=b.combat.max*.7,'Field stamina reset wrong');log('FIELD_QA_PASS '+JSON.stringify({elapsed,health:restoredHealth,maximum,stamina:b.combat.stamina,maxStamina:b.combat.max,checkpoint:b.entity.position,combatMode:b.combat.mode}));
fs.writeFileSync(path.join(root,'field-result.json'),JSON.stringify({status:'PASS',elapsed,health:restoredHealth,maximum,stamina:b.combat.stamina,maxStamina:b.combat.max},null,2));b.quit();}
main().catch(error=>{log('FIELD_QA_FAIL '+error.stack);process.exitCode=1;}).finally(async()=>{for(let b of bots)b.quit();await pause(500);process.exit(process.exitCode??0);});
