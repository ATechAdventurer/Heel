import test from 'node:test';
import assert from 'node:assert/strict';
import {Relay,digest} from '../relay.mjs';
function setup(){
  let clock=1000,saved;
  const relay=new Relay({now:()=>clock,persist:v=>saved=structuredClone(v),enrollmentHashes:['owner-ticket','pet-ticket'].map(digest)});
  const a=relay.register('a','owner-ticket'),b=relay.register('b','pet-ticket');
  const owner=relay.authenticate(a.token),pet=relay.authenticate(b.token),messages=new Map([[owner.id,[]],[pet.id,[]]]);
  for(const peer of [owner,pet])relay.connect(peer,m=>messages.get(peer.id).push(structuredClone(m)),()=>{});
  const send=(peer,m)=>relay.handle(peer,m,peer.id);
  const last=(peer,type)=>messages.get(peer.id).filter(m=>m.type===type).at(-1);
  const pair=()=>{send(owner,{type:'invite',owner:'Owner',pet:'Pet',species:'Fox'});const code=last(owner,'invite').code;send(pet,{type:'preview',code});assert.equal(last(pet,'preview').profile.species,'Fox');send(pet,{type:'accept',code});send(owner,{type:'approve'});send(pet,{type:'device',device:{id:1,name:'Device',features:[{index:0,steps:10,label:'Vibration'}]}});return relay.pair(owner);};
  const ready=p=>send(pet,{type:'ready',epoch:p.epoch});
  const start=p=>{ready(p);send(owner,{type:'start',epoch:p.epoch});};
  return {relay,owner,pet,messages,send,last,pair,ready,start,advance:n=>clock+=n,snapshot:()=>saved};
}
test('legacy one-time secrets remain single-use and admission is closed by default',()=>{
  const r=new Relay({enrollmentHashes:['a','b'].map(digest)});
  assert.throws(()=>r.register('1'),/setup code/);assert.throws(()=>r.register('1','wrong'),/invalid/);
  const a=r.register('1','a');assert.ok(r.authenticate(a.token));assert.equal(r.authenticate('wrong'),null);
  assert.throws(()=>r.register('2','a'),/already used/);r.register('2','b');assert.throws(()=>r.register('3','b'),/already used/);
  assert.throws(()=>new Relay().register('x','anything'),/invalid/);
});

test('a reusable setup code enrolls distinct current and future phones without consuming itself',()=>{
  const r=new Relay({reusableEnrollmentHashes:[digest('reusable-secret')]});
  const phones=Array.from({length:5},(_,i)=>r.register('phone-'+i,'reusable-secret'));
  assert.equal(new Set(phones.map(p=>p.id)).size,5);
  assert.equal(new Set(phones.map(p=>p.token)).size,5);
  for(const phone of phones){assert.equal(r.authenticate(phone.token).id,phone.id);assert.equal(r.authenticate(phone.token).pairId,null);}
  assert.throws(()=>r.register('other','wrong'),/invalid/);
  assert.equal(r.authenticate('reusable-secret'),null);
});

test('enabling and rotating reusable enrollment preserves old phone identities across restart',()=>{
  let data;
  const original=new Relay({enrollmentHashes:['old-a','old-b'].map(digest),persist:v=>data=structuredClone(v)});
  const first=original.register('a','old-a'),second=original.register('b','old-b');
  const upgraded=new Relay({data,reusableEnrollmentHashes:[digest('shared')],persist:v=>data=structuredClone(v)});
  assert.equal(upgraded.authenticate(first.token).id,first.id);
  assert.equal(upgraded.authenticate(second.token).id,second.id);
  assert.throws(()=>upgraded.register('a','old-a'),/already used/);
  const third=upgraded.register('c','shared');
  const restarted=new Relay({data,reusableEnrollmentHashes:[digest('replacement')]});
  for(const phone of [first,second,third])assert.equal(restarted.authenticate(phone.token).id,phone.id);
  assert.throws(()=>restarted.register('d','shared'),/invalid/);
  assert.ok(restarted.register('d','replacement').token);
});

test('reusable enrollment rolls back failed persistence and still requires explicit pairing',()=>{
  let fail=true;
  const r=new Relay({reusableEnrollmentHashes:[digest('shared')],persist:()=>{if(fail)throw Error('disk full');}});
  assert.throws(()=>r.register('a','shared'),/disk full/);assert.equal(r.peers.size,0);
  fail=false;const phone=r.register('a','shared'),peer=r.authenticate(phone.token),messages=[];
  r.connect(peer,m=>messages.push(m),()=>{});
  r.handle(peer,{type:'frame',epoch:'x',seq:1,values:[1]});
  assert.equal(messages.at(-1).type,'error');assert.equal(r.pairs.size,0);
  assert.ok(r.register('b','shared').token);
});
test('failed persistence cannot consume enrollment or leak an identity',()=>{const r=new Relay({enrollmentHashes:[digest('a')],persist:()=>{throw Error('disk full');}});assert.throws(()=>r.register('1','a'),/disk full/);assert.equal(r.peers.size,0);assert.equal(r.enrollments[0].used,false);});
test('pet acceptance and owner approval are required; owner cannot supply device or ready',()=>{const s=setup();s.send(s.owner,{type:'invite',owner:'O',pet:'P',species:'Cat'});const code=s.last(s.owner,'invite').code;s.send(s.pet,{type:'accept',code});const p=s.relay.pair(s.owner);s.send(s.pet,{type:'approve'});assert.equal(p.approved,false);s.send(s.owner,{type:'approve'});assert.equal(p.approved,true);s.send(s.owner,{type:'ready',epoch:p.epoch});assert.equal(p.ready,false);s.send(s.owner,{type:'device',device:{id:1,name:'x',features:[{index:0,steps:10}]}});assert.equal(p.device,undefined);});
test('ready is atomic; Start retains ready epoch and begins at zero',()=>{const s=setup(),p=s.pair();s.messages.get(s.pet.id).length=0;s.ready(p);const states=s.messages.get(s.pet.id).filter(m=>m.type==='state');assert.equal(states.length,1);assert.equal(states[0].ready,true);const epoch=p.epoch;s.send(s.owner,{type:'start',epoch});assert.equal(p.epoch,epoch);assert.equal(p.active,true);assert.deepEqual(p.values,[0]);});
test('Stop is an epoch barrier against queued ready/start/frame/ack',()=>{const s=setup(),p=s.pair();s.start(p);const old=p.epoch;s.send(s.owner,{type:'frame',epoch:old,seq:1,values:[.8]});s.send(s.pet,{type:'pause'});assert.notEqual(p.epoch,old);for(const [peer,m]of [[s.pet,{type:'ready',epoch:old}],[s.owner,{type:'start',epoch:old}],[s.owner,{type:'frame',epoch:old,seq:2,values:[1]}],[s.pet,{type:'applied',epoch:old,seq:1,values:[.8]}]])s.send(peer,m);assert.equal(p.ready,false);assert.equal(p.active,false);assert.deepEqual(p.values,[0]);});
test('sequence increases, rests normalize to zero and acknowledgment follows exact frame',()=>{const s=setup(),p=s.pair();s.start(p);s.send(s.pet,{type:'frame',epoch:p.epoch,seq:1,values:[1]});assert.equal(p.seq,0);s.send(s.owner,{type:'frame',epoch:p.epoch,seq:2,values:[.04],hold:true});assert.deepEqual(s.last(s.pet,'frame').values,[0]);s.send(s.owner,{type:'frame',epoch:p.epoch,seq:1,values:[1]});assert.equal(p.seq,2);s.send(s.pet,{type:'applied',epoch:p.epoch,seq:2,values:[0]});assert.deepEqual(p.values,[0]);assert.equal(p.appliedSeq,2);assert.equal(s.last(s.owner,'applied').hold,true);});
test('expired lease rejects frames before tick and disconnect clears readiness',()=>{const s=setup(),p=s.pair();s.start(p);s.advance(2000);s.send(s.owner,{type:'frame',epoch:p.epoch,seq:1,values:[1]});assert.equal(p.active,false);assert.deepEqual(p.values,[0]);s.send(s.pet,{type:'heartbeat'});s.ready(p);assert.equal(p.ready,true);s.relay.disconnect(s.owner);assert.equal(p.ready,false);});
test('unacknowledged output and idle readiness expire within two seconds',()=>{const s=setup(),p=s.pair();s.start(p);s.send(s.owner,{type:'frame',epoch:p.epoch,seq:1,values:[.5]});s.advance(2000);s.send(s.owner,{type:'heartbeat'});s.send(s.pet,{type:'heartbeat'});s.relay.tick();assert.equal(p.active,false);s.ready(p);s.advance(2000);s.relay.tick();assert.equal(p.ready,false);});
test('restart preserves approved identity and profile, never session or device',()=>{const s=setup(),p=s.pair();s.start(p);const r=new Relay({data:s.snapshot()});const restored=r.pairs.get(p.id);assert.equal(restored.active,false);assert.equal(restored.ready,false);assert.equal(restored.device,null);assert.notEqual(restored.epoch,p.epoch);assert.equal(restored.profile.species,'Fox');assert.equal(r.enrollments.every(x=>x.used),true);});
test('malformed messages do not throw, pending invitations expire and limits stay bounded',()=>{const s=setup();for(const m of [null,[],{},true,{type:'invite',owner:1}])assert.doesNotThrow(()=>s.send(s.owner,m));s.send(s.owner,{type:'invite',owner:'O',pet:'P',species:'Fox'});s.advance(600000);s.relay.tick();assert.equal(s.owner.pairId,null);for(let i=0;i<1100;i++){try{s.relay.limit('attack'+i,1,1000);}catch{}}assert.ok(s.relay.limits.size<=1024);s.advance(1000);s.relay.tick();assert.equal(s.relay.limits.size,0);});
test('attempt notifications are bounded and only accepted from active pet',()=>{const s=setup(),p=s.pair();s.send(s.pet,{type:'attempt',control:0});assert.equal(p.notices.length,0);s.start(p);s.send(s.pet,{type:'attempt',control:0});assert.equal(s.last(s.owner,'notices').unread,1);s.send(s.owner,{type:'read'});assert.equal(p.unread,0);});
test('Safe Word belongs only to paired pet and blocks stale Start/frame until fresh Ready',()=>{const s=setup(),p=s.pair();s.start(p);const epoch=p.epoch;s.send(s.owner,{type:'safeword'});assert.equal(p.active,true);assert.equal(p.epoch,epoch);s.send(s.pet,{type:'safeword'});assert.equal(p.active,false);assert.equal(p.ready,false);assert.equal(p.reason,'Safe Word used · control paused');assert.notEqual(p.epoch,epoch);s.send(s.owner,{type:'start',epoch});s.send(s.owner,{type:'frame',epoch,seq:1,values:[1]});assert.equal(p.active,false);assert.deepEqual(p.values,[0]);const stopped=p.epoch;s.send(s.pet,{type:'safeword'});assert.notEqual(p.epoch,stopped);s.start(p);assert.equal(p.active,true);});

test('temporary Pause keeps permission, requires deliberate Resume, and restarts at zero',()=>{
  const s=setup(),p=s.pair();s.start(p);const old=p.epoch;
  s.send(s.owner,{type:'frame',epoch:old,seq:1,values:[.8],hold:true});s.send(s.pet,{type:'applied',epoch:old,seq:1,values:[.8]});
  s.send(s.owner,{type:'pause'});assert.equal(p.resumeAllowed,true);assert.equal(p.paused,true);
  s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,false);
  s.send(s.owner,{type:'resume',epoch:p.epoch});assert.equal(p.active,true);assert.equal(p.resumed,true);assert.deepEqual(p.values,[0]);assert.equal(p.hold,false);
  s.send(s.owner,{type:'frame',epoch:old,seq:99,values:[1]});assert.equal(p.seq,0);
});
test('transport recovery permits pet recovery at zero and preserves a deliberate pause',()=>{
  const s=setup(),p=s.pair();s.start(p);const old=p.epoch;s.relay.disconnect(s.pet);
  assert.equal(p.active,false);assert.equal(p.resumeAllowed,true);assert.equal(p.paused,false);
  s.relay.connect(s.pet,m=>s.messages.get(s.pet.id).push(structuredClone(m)),()=>{});
  s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,true);assert.notEqual(p.epoch,old);assert.deepEqual(p.values,[0]);
  s.send(s.pet,{type:'pause'});s.relay.disconnect(s.pet);s.relay.connect(s.pet,m=>s.messages.get(s.pet.id).push(structuredClone(m)),()=>{});
  s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,false);assert.equal(p.paused,true);
});
test('Safe Word cannot be cleared by reconnect, Resume, owner Start, or relay restart',()=>{
  const s=setup(),p=s.pair();s.start(p);s.send(s.pet,{type:'safeword'});assert.equal(p.safeWord,true);assert.equal(p.resumeAllowed,false);
  s.relay.disconnect(s.pet);s.relay.connect(s.pet,m=>s.messages.get(s.pet.id).push(structuredClone(m)),()=>{});
  for(const peer of [s.owner,s.pet])s.send(peer,{type:'resume',epoch:p.epoch});
  s.send(s.owner,{type:'start',epoch:p.epoch});assert.equal(p.active,false);assert.equal(p.safeWord,true);
  const restored=new Relay({data:s.snapshot()}).pairs.get(p.id);assert.equal(restored.safeWord,true);assert.equal(restored.active,false);
  s.ready(p);assert.equal(p.safeWord,false);assert.equal(p.ready,true);s.send(s.owner,{type:'start',epoch:p.epoch});assert.equal(p.active,true);
});
test('new devices and acknowledgment failures never inherit recoverable control',()=>{
  const s=setup(),p=s.pair();s.start(p);s.send(s.pet,{type:'device',device:null});assert.equal(p.resumeAllowed,false);
  s.send(s.owner,{type:'resume',epoch:p.epoch});assert.equal(p.active,false);
});

function held(s,p,values=[.6],seq=1){s.send(s.owner,{type:'frame',epoch:p.epoch,seq,values,hold:true});s.send(s.pet,{type:'applied',epoch:p.epoch,seq,values});}
function reconnectPet(s){s.relay.disconnect(s.pet);s.relay.connect(s.pet,m=>s.messages.get(s.pet.id).push(structuredClone(m)),()=>{});}
test('network recovery offers only acknowledged Hold to owner and requires a fresh frame',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p);const old=p.epoch;
  s.send(s.owner,{type:'frame',epoch:old,seq:2,values:[.9],hold:true});
  reconnectPet(s);s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});
  assert.deepEqual(s.last(s.owner,'state').restore.values,[.6]);assert.equal(s.last(s.pet,'state').restore,null);assert.deepEqual(p.values,[0]);assert.equal(p.seq,0);
  s.send(s.owner,{type:'frame',epoch:old,seq:3,values:[1],hold:true});assert.equal(p.seq,0);
  held(s,p,[.6]);assert.deepEqual(p.values,[.6]);assert.equal(p.hold,true);
});
test('repeated disconnects on both phones retain the same held recovery snapshot',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p,[.7]);
  s.relay.disconnect(s.pet);s.relay.disconnect(s.owner);
  for(const peer of [s.pet,s.owner])s.relay.connect(peer,m=>s.messages.get(peer.id).push(structuredClone(m)),()=>{});
  s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.deepEqual(s.last(s.owner,'state').restore.values,[.7]);
  reconnectPet(s);s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.deepEqual(s.last(s.owner,'state').restore.values,[.7]);
});
test('temporary pause during outage discards held recovery and manual Resume stays at zero',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p);s.relay.disconnect(s.owner);s.send(s.pet,{type:'pause'});
  s.relay.connect(s.owner,m=>s.messages.get(s.owner.id).push(structuredClone(m)),()=>{});
  s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,false);
  s.send(s.owner,{type:'resume',epoch:p.epoch});assert.equal(s.last(s.owner,'state').restore,null);assert.deepEqual(p.values,[0]);
});
test('unacknowledged release or zero wins over the previous held output',()=>{
  for(const release of [{values:[0],hold:true},{values:[.8],hold:false}]){
    const s=setup(),p=s.pair();s.start(p);held(s,p);s.advance(2000);
    s.send(s.owner,{type:'frame',epoch:p.epoch,seq:2,...release});s.send(s.pet,{type:'heartbeat'});
    s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});const restore=s.last(s.owner,'state').restore;
    assert.ok(restore===null||restore.values.every(v=>v===0));
  }
});
test('non-held output, Safe Word, device changes and process-reset stops never restore Hold',()=>{
  for(const action of ['nonheld','safeword','device','stop']){
    const s=setup(),p=s.pair();s.start(p);held(s,p);
    if(action==='nonheld'){s.send(s.owner,{type:'frame',epoch:p.epoch,seq:2,values:[.8],hold:false});s.send(s.pet,{type:'applied',epoch:p.epoch,seq:2,values:[.8]});}
    else s.send(s.pet,{type:action,...(action==='device'?{device:null}:{})});
    reconnectPet(s);s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(s.last(s.owner,'state').restore,null);
  }
});
test('held recovery is not written to persistent state or restored after relay restart',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p);reconnectPet(s);s.relay.save();
  assert.equal(s.snapshot().pairs[0].recovery,undefined);const restored=new Relay({data:s.snapshot()}).pairs.get(p.id);
  assert.equal(restored.active,false);assert.equal(restored.resumeAllowed,false);assert.equal(restored.recovery,undefined);
});

test('a pending zero clears only that channel from two-point Hold recovery',()=>{
  const s=setup(),p=s.pair();s.send(s.pet,{type:'device',device:{id:2,name:'Two points',features:[{index:1,steps:20,label:'One'},{index:3,steps:100,label:'Two'}]}});s.start(p);held(s,p,[.4,.65]);
  s.send(s.owner,{type:'frame',epoch:p.epoch,seq:2,values:[0,.9],hold:true});reconnectPet(s);s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.deepEqual(s.last(s.owner,'state').restore.values,[0,.65]);
});

test('Bluetooth drop retains Hold but blocks Resume until the same device link returns',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p,[.6]);const old=p.epoch;
  s.send(s.pet,{type:'device_link',id:p.device.id,connected:false});assert.equal(p.active,false);assert.equal(p.deviceOnline,false);assert.notEqual(p.epoch,old);assert.deepEqual(p.recovery.values,[.6]);
  s.send(s.owner,{type:'resume',epoch:p.epoch});s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,false);
  s.send(s.pet,{type:'device_link',id:p.device.id,connected:true});s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,true);assert.deepEqual(s.last(s.owner,'state').restore.values,[.6]);assert.deepEqual(p.values,[0]);
});
test('Bluetooth recovery preserves a manual Pause and cannot clear Safe Word',()=>{
  for(const action of ['pause','safeword']){
    const s=setup(),p=s.pair();s.start(p);held(s,p);s.send(s.pet,{type:'device_link',id:p.device.id,connected:false});s.send(s.pet,{type:action});
    s.send(s.pet,{type:'device_link',id:p.device.id,connected:true});s.send(s.pet,{type:'resume',epoch:p.epoch,automatic:true});assert.equal(p.active,false);assert.equal(p.recovery,null);
    if(action==='pause'){s.send(s.owner,{type:'resume',epoch:p.epoch});assert.equal(p.active,true);assert.deepEqual(p.values,[0]);assert.equal(p.restore,null);}else assert.equal(p.safeWord,true);
  }
});
test('device-link messages require the paired pet and exact selected device; repeated loss is idempotent',()=>{
  const s=setup(),p=s.pair();s.start(p);held(s,p);s.send(s.owner,{type:'device_link',id:p.device.id,connected:false});assert.equal(p.active,true);
  s.send(s.pet,{type:'device_link',id:999,connected:false});assert.equal(p.active,true);s.send(s.pet,{type:'device_link',id:p.device.id,connected:false});const epoch=p.epoch;
  s.send(s.pet,{type:'device_link',id:p.device.id,connected:false});assert.equal(p.epoch,epoch);assert.deepEqual(p.recovery.values,[.6]);
});
