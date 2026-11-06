import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {mkdtempSync,readFileSync,statSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {WebSocket} from 'ws';
import {createServer,clientIP,enrollmentConfig} from '../server.mjs';
import {digest} from '../relay.mjs';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function connect(base,token){const ws=new WebSocket(base.replace('http:','ws:')+'/v1/ws',{headers:{Authorization:'Bearer '+token}});ws.messages=[];ws.on('message',raw=>ws.messages.push(JSON.parse(raw)));await once(ws,'open');return ws;}
async function message(ws,type,predicate=()=>true){for(let i=0;i<100;i++){const at=ws.messages.findIndex(m=>m.type===type&&predicate(m));if(at>=0)return ws.messages.splice(at,1)[0];await sleep(10);}throw Error('Timed out: '+type);}
const send=(ws,value)=>ws.send(JSON.stringify(value));
async function fixture(t,persist=false){const dir=mkdtempSync(join(tmpdir(),'heel-test-'));const dataFile=persist?join(dir,'state.json'):null;const app=createServer({dataFile,enrollmentHashes:['owner','pet'].map(digest)});app.server.listen(0,'127.0.0.1');await once(app.server,'listening');const base='http://127.0.0.1:'+app.server.address().port;t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});const register=async code=>{const r=await fetch(base+'/v1/register',{method:'POST',headers:{'X-Heel-Enrollment':code}});return {status:r.status,body:await r.json()};};return {...app,base,register,dataFile};}

test('enrollment configuration accepts legacy arrays and explicit reusable hashes; invalid input fails closed',()=>{
  const hash=digest('shared');assert.deepEqual(enrollmentConfig([hash]),{enrollmentHashes:[hash]});
  assert.deepEqual(enrollmentConfig({reusableHashes:[hash]}),{enrollmentHashes:[],reusableEnrollmentHashes:[hash]});
  for(const value of [null,'shared',{reusableHashes:['plaintext']},{reusableHashes:'not-an-array'},{unexpected:true}])assert.throws(()=>enrollmentConfig(value));
});

test('unchanged Android registration endpoint can reuse one code for three independent WSS identities',async t=>{
  const app=createServer({reusableEnrollmentHashes:[digest('shared-code')]});
  app.server.listen(0,'127.0.0.1');await once(app.server,'listening');t.after(()=>app.close());
  const base='http://127.0.0.1:'+app.server.address().port,ids=new Set();
  for(let i=0;i<3;i++){
    const response=await fetch(base+'/v1/register',{method:'POST',headers:{'X-Heel-Enrollment':'shared-code'}});
    assert.equal(response.status,200);const identity=await response.json();ids.add(identity.id);
    const ws=await connect(base,identity.token);assert.equal((await message(ws,'state')).phase,'unpaired');ws.terminate();
  }
  assert.equal(ids.size,3);assert.equal(app.relay.peers.size,3);
});
test('proxy headers cannot bypass IP accounting unless trusted loopback configured',()=>{const req={socket:{remoteAddress:'203.0.113.1'},headers:{'cf-connecting-ip':'1.2.3.4','x-real-ip':'8.8.8.8'}};assert.equal(clientIP(req,true),'203.0.113.1');req.socket.remoteAddress='127.0.0.1';assert.equal(clientIP(req),'127.0.0.1');assert.equal(clientIP(req,true),'8.8.8.8');req.headers['x-real-ip']='1.1.1.1, 2.2.2.2';assert.equal(clientIP(req,true),'127.0.0.1');});
test('HTTP enrollment, private atomic persistence and unauthorized upgrade',async t=>{const f=await fixture(t,true);assert.equal((await fetch(f.base+'/health')).status,200);assert.equal((await f.register('wrong')).status,429);const a=await f.register('owner');assert.equal(a.status,200,JSON.stringify(a.body));assert.equal((await f.register('owner')).status,429);const raw=readFileSync(f.dataFile,'utf8');assert.equal(raw.includes(a.body.token),false);assert.equal(JSON.parse(raw).enrollments[0].used,true);if(process.platform!=='win32')assert.equal(statSync(f.dataFile).mode&0o777,0o600);const ws=new WebSocket(f.base.replace('http:','ws:')+'/v1/ws');const [err]=await once(ws,'error');assert.match(err.message,/401/);});
test('real WebSockets pair, ready, start, normalize, ack, Stop barrier and disconnect',async t=>{const f=await fixture(t);const a=await f.register('owner'),b=await f.register('pet');const owner=await connect(f.base,a.body.token),pet=await connect(f.base,b.body.token);send(owner,{type:'invite',owner:'O',pet:'P',species:'Fox'});const invite=await message(owner,'invite');send(pet,{type:'preview',code:invite.code});assert.equal((await message(pet,'preview')).profile.owner,'O');send(pet,{type:'accept',code:invite.code});await message(owner,'state',m=>m.phase==='approval');send(owner,{type:'approve'});await message(pet,'state',m=>m.phase==='paired');send(pet,{type:'device',device:{id:0,name:'Device',features:[{index:0,steps:10,label:'Motor'}]}});const device=await message(pet,'state',m=>!!m.device);send(pet,{type:'ready',epoch:device.epoch});const ready=await message(owner,'state',m=>m.ready);send(owner,{type:'start',epoch:ready.epoch});const active=await message(pet,'state',m=>m.active);assert.equal(active.epoch,ready.epoch);assert.deepEqual(active.values,[0]);send(owner,{type:'frame',epoch:active.epoch,seq:1,values:[.56],hold:true});const frame=await message(pet,'frame');assert.deepEqual(frame.values,[.6]);send(pet,{type:'applied',epoch:frame.epoch,seq:frame.seq,values:frame.values});assert.deepEqual((await message(owner,'applied')).values,[.6]);send(pet,{type:'pause'});const stopped=await message(owner,'state',m=>!m.active&&m.epoch!==active.epoch&&m.reason==='Paused by pet');send(owner,{type:'frame',epoch:active.epoch,seq:2,values:[1]});await sleep(30);assert.equal(pet.messages.some(m=>m.type==='frame'),false);send(pet,{type:'ready',epoch:stopped.epoch});await message(owner,'state',m=>m.ready);owner.terminate();await message(pet,'state',m=>m.phase==='paired'&&!m.online&&m.reason==='Connection interrupted · reconnecting');assert.equal(f.relay.pair(f.relay.authenticate(b.body.token)).ready,false);pet.terminate();});
test('new socket replaces old identity and malformed/binary payload closes transport',async t=>{const f=await fixture(t),a=await f.register('owner');const old=await connect(f.base,a.body.token),closed=once(old,'close');const replacement=await connect(f.base,a.body.token);await closed;assert.equal(f.relay.sockets.size,1);const invalidClosed=once(replacement,'close');replacement.send('{');assert.equal((await invalidClosed)[0],1008);const third=await connect(f.base,a.body.token);const binaryClosed=once(third,'close');third.send(Buffer.from('{}'));assert.equal((await binaryClosed)[0],1008);});




test('real WebSocket recovery restores held levels only after a fresh owner frame',async t=>{
  const f=await fixture(t),a=await f.register('owner'),b=await f.register('pet');
  const owner=await connect(f.base,a.body.token);let pet=await connect(f.base,b.body.token);
  send(owner,{type:'invite',owner:'O',pet:'P',species:'Puppy'});const invite=await message(owner,'invite');
  send(pet,{type:'accept',code:invite.code});await message(owner,'state',m=>m.phase==='approval');send(owner,{type:'approve'});await message(pet,'state',m=>m.phase==='paired');
  send(pet,{type:'device',device:{id:7,name:'Lovense',features:[{index:0,steps:20,label:'Vibration'}]}});const device=await message(pet,'state',m=>!!m.device);
  send(pet,{type:'ready',epoch:device.epoch});const ready=await message(owner,'state',m=>m.ready);send(owner,{type:'start',epoch:ready.epoch});const active=await message(pet,'state',m=>m.active);
  send(owner,{type:'frame',epoch:active.epoch,seq:1,values:[.65],hold:true});const initial=await message(pet,'frame');send(pet,{type:'applied',epoch:initial.epoch,seq:1,values:initial.values});await message(owner,'applied');
  const closed=once(pet,'close');pet.terminate();await closed;await message(owner,'state',m=>m.phase==='paired'&&!m.online);
  pet=await connect(f.base,b.body.token);const back=await message(pet,'state',m=>m.phase==='paired'&&m.online);
  send(pet,{type:'resume',epoch:back.epoch,automatic:true});const recovery=await message(owner,'state',m=>m.active&&!!m.restore);assert.deepEqual(recovery.restore.values,[.65]);assert.deepEqual(recovery.values,[0]);assert.notEqual(recovery.epoch,active.epoch);
  await message(pet,'state',m=>m.active);assert.equal(pet.messages.some(m=>m.type==='frame'),false);
  send(owner,{type:'frame',epoch:recovery.epoch,seq:1,values:recovery.restore.values,hold:true});const restored=await message(pet,'frame');assert.equal(restored.epoch,recovery.epoch);
  send(pet,{type:'applied',epoch:restored.epoch,seq:1,values:restored.values});assert.deepEqual((await message(owner,'applied')).values,[.65]);owner.terminate();pet.terminate();
});

test('real WebSocket Bluetooth recovery retains Hold without replacing either phone socket',async t=>{
  const f=await fixture(t),a=await f.register('owner'),b=await f.register('pet');
  const owner=await connect(f.base,a.body.token);let pet=await connect(f.base,b.body.token);
  send(owner,{type:'invite',owner:'O',pet:'P',species:'Puppy'});const invite=await message(owner,'invite');
  send(pet,{type:'accept',code:invite.code});await message(owner,'state',m=>m.phase==='approval');send(owner,{type:'approve'});await message(pet,'state',m=>m.phase==='paired');
  send(pet,{type:'device',device:{id:7,name:'Lovense',features:[{index:0,steps:20,label:'Vibration'}]}});const device=await message(pet,'state',m=>!!m.device);
  send(pet,{type:'ready',epoch:device.epoch});const ready=await message(owner,'state',m=>m.ready);send(owner,{type:'start',epoch:ready.epoch});const active=await message(pet,'state',m=>m.active);
  send(owner,{type:'frame',epoch:active.epoch,seq:1,values:[.65],hold:true});const initial=await message(pet,'frame');send(pet,{type:'applied',epoch:initial.epoch,seq:1,values:initial.values});await message(owner,'applied');
  send(pet,{type:'device_link',id:7,connected:false});await message(owner,'state',m=>m.deviceOnline===false);
  send(pet,{type:'device_link',id:7,connected:true});const back=await message(pet,'state',m=>m.deviceOnline===true&&!m.active&&m.epoch!==active.epoch);
  send(pet,{type:'resume',epoch:back.epoch,automatic:true});const recovery=await message(owner,'state',m=>m.active&&!!m.restore);assert.deepEqual(recovery.restore.values,[.65]);assert.deepEqual(recovery.values,[0]);assert.notEqual(recovery.epoch,active.epoch);
  await message(pet,'state',m=>m.active);assert.equal(pet.messages.some(m=>m.type==='frame'),false);
  send(owner,{type:'frame',epoch:recovery.epoch,seq:1,values:recovery.restore.values,hold:true});const restored=await message(pet,'frame');assert.equal(restored.epoch,recovery.epoch);
  send(pet,{type:'applied',epoch:restored.epoch,seq:1,values:restored.values});assert.deepEqual((await message(owner,'applied')).values,[.65]);owner.terminate();pet.terminate();
});
