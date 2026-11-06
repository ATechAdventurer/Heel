import {randomBytes,randomUUID,createHash} from 'node:crypto';
export const digest=s=>createHash('sha256').update(s).digest('hex');
const alphabet='23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const code=()=>Array.from(randomBytes(10),n=>alphabet[n%alphabet.length]).join('');
const normalize=(v,steps)=>{if(v<=.05)return 0;const q=steps>0?Math.round(v*steps)/steps:v;return q<=.05?0:q;};
const name=v=>{if(typeof v!=='string'||!v.trim()||v.length>40||/[\u0000-\u001f]/.test(v))throw Error('Use 1–40 characters for each name and type.');return v.trim();};
export class Relay {
  constructor({now=()=>Date.now(),persist=()=>{},data={},enrollmentHashes=[],reusableEnrollmentHashes=[]}={}){
    this.now=now;this.persist=persist;this.peers=new Map();this.pairs=new Map();this.sockets=new Map();this.limits=new Map();
    this.enrollments=data.enrollments??enrollmentHashes.map(hash=>({hash,used:false}));
    if(this.enrollments.length>2||this.enrollments.some(e=>!/^[a-f0-9]{64}$/.test(e.hash)))throw Error('Invalid enrollment configuration.');
    // Reusable admission codes come only from configuration, so rotating one never
    // invalidates existing phone identities or revives a retired code from state.
    if(!Array.isArray(reusableEnrollmentHashes)||reusableEnrollmentHashes.length>2||reusableEnrollmentHashes.some(hash=>typeof hash!=='string'||!/^[a-f0-9]{64}$/.test(hash)))throw Error('Invalid reusable enrollment configuration.');
    this.reusableEnrollmentHashes=new Set(reusableEnrollmentHashes);
    if((data.peers||[]).length>10000)throw Error('Relay identity storage is at capacity.');
    for(const p of data.peers||[])this.peers.set(p.id,p);
    for(const p of data.pairs||[])if(p.pet&&p.approved){this.pairs.set(p.id,{...p,device:null,ready:false,active:false,epoch:randomUUID(),seq:0,appliedSeq:0,values:[],hold:false,reason:p.safeWord?'Safe Word used · control paused':'Relay restarted',safeWord:!!p.safeWord,paused:false,resumeAllowed:false,notices:[],unread:0});}
    for(const p of this.peers.values())if(!this.pairs.has(p.pairId))p.pairId=null;
  }
  save(){this.persist({enrollments:this.enrollments,peers:[...this.peers.values()],pairs:[...this.pairs.values()].filter(p=>p.approved).map(p=>({id:p.id,owner:p.owner,pet:p.pet,approved:true,profile:p.profile,safeWord:!!p.safeWord}))});}
  limit(key,count,ms){const now=this.now();let l=this.limits.get(key);if(!l||now-l.at>=ms){if(!l&&this.limits.size>=1024)throw Error('Too many attempts. Please wait and try again.');l={at:now,n:0,ms};this.limits.set(key,l);}if(++l.n>count)throw Error('Too many attempts. Please wait and try again.');}
  register(ip,enrollment){
    this.limit('register:'+ip,6,60000);
    if(typeof enrollment!=='string'||enrollment.length>100)throw Error('A valid relay setup code is required.');
    const hash=digest(enrollment.trim()),reusable=this.reusableEnrollmentHashes.has(hash);
    const ticket=this.enrollments.find(e=>!e.used&&e.hash===hash);
    if(!reusable&&!ticket)throw Error('Enrollment code is invalid or already used.');
    if(this.peers.size>=10000)throw Error('Relay identity storage is at capacity.');
    const token=randomBytes(32).toString('base64url'),p={id:randomUUID(),hash:digest(token),pairId:null};
    this.peers.set(p.id,p);if(!reusable)ticket.used=true;
    try{this.save();}catch(e){this.peers.delete(p.id);if(!reusable)ticket.used=false;throw e;}
    return {token,id:p.id};
  }
  authenticate(token){if(typeof token!=='string'||token.length>100)return null;const h=digest(token);return [...this.peers.values()].find(p=>p.hash===h)||null;}
  connect(peer,send,close){const old=this.sockets.get(peer.id);if(old){this.sockets.delete(peer.id);old.close();}this.sockets.set(peer.id,{send,close,at:this.now()});const p=this.pairs.get(peer.pairId);if(p)this.breather(p,'Connection restored',!!p.paused);this.state(peer);}
  disconnect(peer){this.sockets.delete(peer.id);const p=this.pairs.get(peer.pairId);if(p)this.breather(p,'Connection interrupted · reconnecting',!!p.paused);}
  send(id,data){this.sockets.get(id)?.send(data);}
  pair(peer){const p=this.pairs.get(peer.pairId);if(!p)throw Error('Pair your phones first.');return p;}
  role(peer,p){return p.owner===peer.id?'owner':p.pet===peer.id?'pet':p.pending===peer.id?'pending':'none';}
  both(p){return this.sockets.has(p.owner)&&this.sockets.has(p.pet);}
  fresh(p){return this.both(p)&&[p.owner,p.pet].every(id=>this.now()-this.sockets.get(id).at<2000);}
  view(peer,p){if(!p)return {type:'state',phase:'unpaired',role:null};const role=this.role(peer,p);return {type:'state',phase:p.approved?'paired':p.pending?'approval':'inviting',role,profile:p.profile,confirmation:p.confirmation||'',ready:!!p.ready,active:!!p.active,safeWord:!!p.safeWord,paused:!!p.paused,resumeAllowed:!!p.resumeAllowed,resumed:!!p.resumed,restore:role==='owner'&&p.active&&p.seq===0?p.restore||null:null,epoch:p.epoch,online:this.both(p),device:p.device||null,deviceOnline:!!p.device&&p.deviceOnline!==false,values:p.values||[],hold:!!p.hold,reason:p.reason||'',notices:role==='owner'?p.notices||[]:[],unread:role==='owner'?p.unread||0:0};}
  state(peer){this.send(peer.id,this.view(peer,this.pairs.get(peer.pairId)));}
  broadcast(p){for(const id of new Set([p.owner,p.pet,p.pending].filter(Boolean))){const peer=this.peers.get(id);if(peer)this.send(id,this.view(peer,p));}}
  reset(p,reason){p.recovery=null;p.restore=null;p.requestedHold=false;p.requestedValues=[];p.ackHold=false;p.resumed=false;p.active=false;p.ready=false;p.hold=false;p.values=(p.device?.features||[]).map(()=>0);p.epoch=randomUUID();p.seq=0;p.appliedSeq=0;p.frames=new Map();p.reason=reason;}
  pause(p,reason){p.resumeAllowed=false;p.paused=false;this.reset(p,reason);this.broadcast(p);}
  recoverySnapshot(p){
    if(!p.resumeAllowed||p.safeWord||p.paused||!p.device)return null;
    if(!p.active||p.appliedSeq===0)return p.recovery||null;
    if(!p.ackHold||!p.requestedHold)return null;
    return {device:structuredClone(p.device),hold:true,values:p.values.map((v,i)=>p.requestedValues[i]===0?0:v)};
  }
  breather(p,reason,manual=true){const recovery=manual?null:this.recoverySnapshot(p);this.reset(p,reason);p.recovery=recovery;p.paused=manual;this.broadcast(p);}
  unlink(p){this.pause(p,'Unpaired');for(const id of [p.owner,p.pet,p.pending]){const a=this.peers.get(id);if(a&&a.pairId===p.id){a.pairId=null;this.state(a);}}this.pairs.delete(p.id);this.save();}
  checkCode(raw){if(typeof raw!=='string')throw Error('Enter the invitation code.');const h=digest(raw.toUpperCase().replace(/[\s-]/g,''));const p=[...this.pairs.values()].find(p=>p.codeHash===h&&!p.approved&&this.now()<p.expires);if(!p)throw Error('That code is invalid, expired, or already used.');return p;}
  handle(peer,m,ip='local'){
    try{
      if(!m||typeof m!=='object'||Array.isArray(m)||typeof m.type!=='string')throw Error('Invalid message.');
      const socket=this.sockets.get(peer.id);if(!socket)return;socket.at=this.now();
      this.limit('messages:'+peer.id,150,1000);
      if(m.type==='heartbeat'){const p=this.pairs.get(peer.pairId);this.send(peer.id,{type:'lease',epoch:p?.epoch||'',active:!!p?.active,peerFresh:!!p&&this.both(p)&&[p.owner,p.pet].every(id=>this.now()-this.sockets.get(id).at<2000)});return;}
      if(m.type==='invite'){
        const previous=this.pairs.get(peer.pairId);if(previous?.approved)throw Error('Unpair before making a new invitation.');if(previous)this.unlink(previous);
        this.limit('invite:'+peer.id,6,60000);const c=code(),p={id:randomUUID(),owner:peer.id,pet:null,pending:null,approved:false,profile:{owner:name(m.owner),pet:name(m.pet),species:name(m.species)},codeHash:digest(c),expires:this.now()+600000,epoch:randomUUID(),values:[],notices:[],unread:0};
        peer.pairId=p.id;this.pairs.set(p.id,p);this.save();this.send(peer.id,{type:'invite',code:c.slice(0,5)+'-'+c.slice(5),expires:p.expires});this.broadcast(p);return;
      }
      if(m.type==='preview'||m.type==='accept'){
        this.limit('codes:'+ip,12,60000);this.limit('codes:'+peer.id,6,60000);
        if(this.pairs.has(peer.pairId))throw Error('Unpair before joining another owner.');
        const p=this.checkCode(m.code);if(p.owner===peer.id)throw Error('Use the invitation on the other phone.');if(p.pending&&p.pending!==peer.id)throw Error('That invitation is already awaiting approval.');
        if(m.type==='preview'){this.send(peer.id,{type:'preview',profile:p.profile});return;}
        p.pending=peer.id;peer.pairId=p.id;p.confirmation=String(randomBytes(2).readUInt16BE()%10000).padStart(4,'0');this.broadcast(p);return;
      }
      const p=this.pair(peer),role=this.role(peer,p);
      if(m.type==='unpair'){this.unlink(p);return;}
      if(m.type==='pause'){this.breather(p,role==='pet'?'Paused by pet':'Paused by owner');return;}
      if(m.type==='stop'){this.pause(p,'Control stopped · ready again to begin');return;}
      if(m.type==='safeword'){
        if(role!=='pet'||!p.approved)throw Error('Only the paired pet phone can use Safe Word.');
        p.safeWord=true;this.pause(p,'Safe Word used · control paused');this.save();return;
      }
      if(m.type==='approve'){
        if(role!=='owner'||!p.pending||p.approved||this.now()>=p.expires)throw Error('No valid request to approve.');
        p.pet=p.pending;p.pending=null;p.approved=true;p.codeHash=null;p.confirmation='';this.pause(p,'Paired · connect a device');this.save();return;
      }
      if(!p.approved)throw Error('Finish pairing first.');
      if(m.type==='device_link'){
        if(role!=='pet'||!p.device||m.id!==p.device.id||typeof m.connected!=='boolean')throw Error('Invalid device connection update.');
        if((p.deviceOnline!==false)===m.connected)return;
        p.deviceOnline=m.connected;
        if(!m.connected)this.breather(p,'Finding your toy again…',!!p.paused);
        else {p.reason=p.paused?'Paused · toy reconnected':'Toy reconnected';this.broadcast(p);}
        return;
      }
      if(m.type==='device'){
        if(role!=='pet')throw Error('Only the pet phone can supply device controls.');
        let d=null;if(m.device!==null){const v=m.device;if(!v||!Number.isInteger(v.id)||v.id<0||typeof v.name!=='string'||v.name.length>120||!Array.isArray(v.features)||v.features.length<1||v.features.length>2)throw Error('Invalid device.');const seen=new Set();d={id:v.id,name:v.name,features:v.features.map(f=>{if(!Number.isInteger(f.index)||f.index<0||f.index>63||seen.has(f.index)||!Number.isInteger(f.steps)||f.steps<0||f.steps>10000)throw Error('Invalid device capability.');seen.add(f.index);return {index:f.index,steps:f.steps,label:typeof f.label==='string'?f.label.slice(0,80):'Vibration'};})};}
        if(JSON.stringify(d)!==JSON.stringify(p.device||null)){p.device=d;p.deviceOnline=!!d;this.pause(p,d?'Device ready':'Device disconnected · output unconfirmed');}return;
      }
      if(m.type==='ready'){
        if(role!=='pet'||!p.device||p.deviceOnline===false||!this.fresh(p)||m.epoch!==p.epoch)throw Error('Connect a device and both phones first, then ready again.');p.safeWord=false;p.paused=false;p.resumeAllowed=false;this.reset(p,'Pet is ready');p.ready=true;this.save();this.broadcast(p);return;
      }
      if(m.type==='start'){
        if(role!=='owner'||!p.ready||!p.device||p.deviceOnline===false||!this.fresh(p)||m.epoch!==p.epoch)throw Error('Wait for the pet to get ready.');p.seq=0;p.appliedSeq=0;p.active=true;p.ready=false;p.resumeAllowed=true;p.paused=false;p.reason='Session active';p.values=p.device.features.map(()=>0);this.broadcast(p);return;
      }
      if(m.type==='resume'){
        if(!['owner','pet'].includes(role)||!p.resumeAllowed||p.safeWord||!p.device||p.deviceOnline===false||!this.fresh(p)||m.epoch!==p.epoch||p.active)throw Error('This session cannot resume. Ask the pet to get ready.');
        if(m.automatic&&(role!=='pet'||p.paused))throw Error('A paused session needs a deliberate Resume.');
        const restore=m.automatic&&p.recovery&&JSON.stringify(p.recovery.device)===JSON.stringify(p.device)?p.recovery:null;
        this.reset(p,restore?'Together again · restoring Hold':'Together again · starting at rest');p.active=true;p.resumed=true;p.paused=false;
        // The still-authorized owner must issue a fresh frame in this new epoch.
        // Reconnecting a restarted owner app cannot itself play an old setpoint.
        p.restore=restore;p.recovery=restore;this.broadcast(p);return;
      }
      if(m.type==='frame'){
        if(role!=='owner')throw Error('The pet controls are locked.');
        if(!p.active||p.deviceOnline===false||m.epoch!==p.epoch||!Number.isSafeInteger(m.seq)||m.seq<=p.seq)return;
        if(!Array.isArray(m.values)||m.values.length!==p.device.features.length||m.values.some(v=>typeof v!=='number'||!Number.isFinite(v)||v<0||v>1))throw Error('Invalid control values.');
        p.requestedHold=!!m.hold;p.requestedValues=m.values.map((v,i)=>normalize(v,p.device.features[i].steps));
        if(!p.requestedHold)p.recovery=null;
        else if(p.recovery)p.recovery.values=p.recovery.values.map((v,i)=>p.requestedValues[i]===0?0:v);
        if(!this.fresh(p)){this.breather(p,'Connection lost',false);return;}
        if(p.frames.size>=32){this.pause(p,'Device acknowledgments delayed');return;}
        p.seq=m.seq;p.hold=!!m.hold;
        const frame={type:'frame',epoch:p.epoch,seq:p.seq,values:m.values.map((v,i)=>normalize(v,p.device.features[i].steps)),hold:p.hold};p.frames.set(p.seq,{...frame,at:this.now()});this.send(p.pet,frame);return;
      }
      if(m.type==='applied'){
        if(role!=='pet'||!p.active||m.epoch!==p.epoch||!Number.isSafeInteger(m.seq)||m.seq<=p.appliedSeq||m.seq>p.seq)return;
        if(!Array.isArray(m.values)||m.values.length!==p.device.features.length||m.values.some(v=>typeof v!=='number'||!Number.isFinite(v)||v<0||v>1))throw Error('Invalid acknowledgment.');
        const frame=p.frames.get(m.seq);if(!frame||!this.fresh(p)||this.now()-frame.at>=2000){this.pause(p,'Device acknowledgment expired');return;}const values=m.values.map((v,i)=>normalize(v,p.device.features[i].steps));if(values.some((v,i)=>v!==frame.values[i])){this.pause(p,'Device acknowledgment mismatch');return;}
        p.appliedSeq=m.seq;p.values=values;p.hold=frame.hold;p.ackHold=frame.hold;for(const seq of p.frames.keys())if(seq<=m.seq)p.frames.delete(seq);
        const ack={type:'applied',epoch:p.epoch,seq:m.seq,values:p.values,hold:p.hold};this.send(p.owner,ack);this.send(p.pet,ack);return;
      }
      if(m.type==='attempt'){
        if(role!=='pet'||!p.active)return;this.limit('attempt:'+peer.id,8,1000);
        const control=m.control==='Hold'?'Hold':p.device.features.find(f=>f.index===m.control)?.label;
        if(!control)return;const now=this.now();let n=p.notices[0];
        if(!n||n.control!==control||now-n.first>10000){n={control,first:now,count:0};p.notices.unshift(n);p.notices=p.notices.slice(0,20);p.unread=Math.min(20,p.unread+1);}n.count++;
        this.send(p.owner,{type:'notices',notices:p.notices,unread:p.unread});return;
      }
      if(m.type==='read'){if(role==='owner'){p.unread=0;this.send(p.owner,{type:'notices',notices:p.notices,unread:0});}return;}
      throw Error('Unknown action.');
    }catch(e){this.send(peer.id,{type:'error',message:e.message});}
  }
  tick(){const now=this.now();for(const p of [...this.pairs.values()]){if(!p.approved&&now>=p.expires){this.unlink(p);continue;}if((p.active||p.ready)&&!this.fresh(p))this.breather(p,'Connection lost · reconnecting',false);else if((p.active||p.ready)&&[...(p.frames?.values()||[])].some(f=>now-f.at>=2000))this.pause(p,'Device acknowledgments delayed');}for(const[k,v]of this.limits)if(now-v.at>=v.ms)this.limits.delete(k);}
}

