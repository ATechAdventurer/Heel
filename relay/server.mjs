import http from 'node:http';
import {readFileSync,writeFileSync,mkdirSync,renameSync,existsSync,chmodSync,openSync,fsyncSync,closeSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {isIP} from 'node:net';
import {WebSocketServer} from 'ws';
import {Relay} from './relay.mjs';

// Trust X-Real-IP only from an explicitly enabled loopback reverse proxy.
export function clientIP(req,trustLoopbackProxy=false){
  const remote=req.socket.remoteAddress;
  if(trustLoopbackProxy&&['127.0.0.1','::1','::ffff:127.0.0.1'].includes(remote)){
    const real=req.headers['x-real-ip'];
    if(typeof real==='string'&&isIP(real))return real;
  }
  return remote||'unknown';
}
export function enrollmentConfig(value){
  if(Array.isArray(value))return {enrollmentHashes:value}; // Existing installations.
  if(!value||typeof value!=='object'||Object.keys(value).some(k=>!['oneTimeHashes','reusableHashes'].includes(k)))throw Error('Invalid enrollment configuration.');
  const enrollmentHashes=value.oneTimeHashes??[],reusableEnrollmentHashes=value.reusableHashes??[];
  for(const hashes of [enrollmentHashes,reusableEnrollmentHashes])if(!Array.isArray(hashes)||hashes.length>2||hashes.some(h=>typeof h!=='string'||!/^[a-f0-9]{64}$/.test(h)))throw Error('Invalid enrollment configuration.');
  return {enrollmentHashes,reusableEnrollmentHashes};
}
export function createServer({dataFile=null,now,enrollmentHashes=[],reusableEnrollmentHashes=[],trustLoopbackProxy=false}={}){
  const data=dataFile&&existsSync(dataFile)?JSON.parse(readFileSync(dataFile,'utf8')):{};
  const relay=new Relay({now,data,enrollmentHashes,reusableEnrollmentHashes,persist:value=>{
    if(!dataFile)return;
    mkdirSync(dirname(dataFile),{recursive:true,mode:0o700});
    writeFileSync(dataFile+'.tmp',JSON.stringify(value),{mode:0o600});
    chmodSync(dataFile+'.tmp',0o600);
    const fd=openSync(dataFile+'.tmp','r+');try{fsyncSync(fd);}finally{closeSync(fd);}
    renameSync(dataFile+'.tmp',dataFile);
    if(process.platform!=='win32'){const dir=openSync(dirname(dataFile),'r');try{fsyncSync(dir);}finally{closeSync(dir);}}
  }});
  const ip=req=>clientIP(req,trustLoopbackProxy);
  let stopping=false;
  const server=http.createServer({maxHeaderSize:8192,requestTimeout:5000,headersTimeout:5000,keepAliveTimeout:5000},(req,res)=>{
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Content-Type','application/json');
    if(stopping){res.writeHead(503);res.end('{"error":"Restarting"}');return;}
    if(req.method==='GET'&&req.url==='/health'){res.end(JSON.stringify({service:'Heel relay',version:'0.8',ok:true}));return;}
    if(req.method==='POST'&&req.url==='/v1/register'&&!req.headers.origin){
      if(req.headers['transfer-encoding']||Number(req.headers['content-length']||0)>0){req.resume();res.writeHead(413);res.end('{"error":"Request body not accepted"}');return;}
      try{res.end(JSON.stringify(relay.register(ip(req),req.headers['x-heel-enrollment'])));}catch(e){res.writeHead(429);res.end(JSON.stringify({error:e.message}));}return;
    }
    req.resume();res.writeHead(404);res.end('{"error":"Not found"}');
  });
  server.maxConnections=64;
  const wss=new WebSocketServer({noServer:true,maxPayload:16384,perMessageDeflate:false});
  server.on('upgrade',(req,socket,head)=>{
    socket.on('error',()=>{});
    let peer;
    try{relay.limit('upgrade:'+ip(req),120,60000);peer=relay.authenticate((req.headers.authorization||'').replace(/^Bearer /,''));}catch{socket.end('HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n');return;}
    if(stopping||req.url!=='/v1/ws'||!peer||req.headers.origin){socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');return;}
    wss.handleUpgrade(req,socket,head,ws=>{
      const send=value=>{if(ws.readyState!==1)return;if(ws.bufferedAmount>8192){ws.terminate();return;}ws.send(JSON.stringify(value));};
      // In-flight callbacks from a replaced transport cannot alter its successor.
      relay.connect(peer,send,()=>ws.terminate());
      ws.on('message',(raw,isBinary)=>{
        if(relay.sockets.get(peer.id)?.send!==send)return;
        if(isBinary){ws.close(1008,'Text JSON required');return;}
        try{relay.handle(peer,JSON.parse(raw.toString()),ip(req));}catch{ws.close(1008,'Invalid JSON');}
      });
      ws.on('error',()=>{});ws.on('close',()=>{if(relay.sockets.get(peer.id)?.send===send)relay.disconnect(peer);});
    });
  });
  const timer=setInterval(()=>relay.tick(),250);timer.unref();
  let closing;
  const close=()=>closing??=(async()=>{
    stopping=true;clearInterval(timer);
    for(const p of relay.pairs.values())relay.pause(p,'Relay restarting · ready again to resume');
    for(const client of wss.clients)client.close(1001,'Relay restarting');
    const force=setTimeout(()=>{for(const client of wss.clients)client.terminate();server.closeAllConnections();},500);force.unref();
    await new Promise(r=>server.close(r));clearTimeout(force);wss.close();
  })();
  return {server,relay,close};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const enrollment=enrollmentConfig(process.env.HEEL_ENROLLMENT_FILE?JSON.parse(readFileSync(process.env.HEEL_ENROLLMENT_FILE,'utf8')):[]);
  const {server,close}=createServer({dataFile:process.env.HEEL_DATA_FILE||resolve('.runtime/state.json'),...enrollment,trustLoopbackProxy:process.env.HEEL_TRUST_LOOPBACK_PROXY==='1'});
  server.listen(Number(process.env.PORT||8787),process.env.HOST||'127.0.0.1',()=>console.log('Heel relay listening on loopback port '+server.address().port));
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>{close().then(()=>process.exit(0),()=>process.exit(1));});
}


