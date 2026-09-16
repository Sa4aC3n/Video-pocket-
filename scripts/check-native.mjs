import fs from 'node:fs';
import zlib from 'node:zlib';
import path from 'node:path';
// ZIP reader for trusted build artifacts only, including Python zipapps with a shebang prefix.
export function entries(b) {
  const end = b.lastIndexOf(Buffer.from([80,75,5,6]));
  if(end < 0) return [];
  const count=b.readUInt16LE(end+10), size=b.readUInt32LE(end+12), offset=b.readUInt32LE(end+16);
  const prefix=end-size-offset; let cursor=offset+prefix; const out=[];
  for(let i=0;i<count;i++) {
    if(b.readUInt32LE(cursor)!==0x02014b50) throw Error('Invalid central directory');
    const method=b.readUInt16LE(cursor+10), compressed=b.readUInt32LE(cursor+20);
    const nameLen=b.readUInt16LE(cursor+28), extra=b.readUInt16LE(cursor+30), comment=b.readUInt16LE(cursor+32);
    const name=b.subarray(cursor+46,cursor+46+nameLen).toString();
    const local=b.readUInt32LE(cursor+42)+prefix;
    const start=local+30+b.readUInt16LE(local+26)+b.readUInt16LE(local+28);
    const bytes=b.subarray(start,start+compressed);
    out.push({name,data:()=> method===0?bytes:method===8?zlib.inflateRawSync(bytes):(()=>{throw Error('Unsupported compression');})()});
    cursor+=46+nameLen+extra+comment;
  }
  return out;
}
const file=process.argv[2];
if(file) {
  const findings=[];
  function scan(b,name,depth=0) {
    if(b.subarray(0,4).equals(Buffer.from([127,69,76,70]))) {
      if(b[4]!==2 || b[5]!==1) return;
      const off=Number(b.readBigUInt64LE(32)), stride=b.readUInt16LE(54), count=b.readUInt16LE(56);
      const aligns=[];
      for(let i=0;i<count;i++){const p=off+i*stride;if(b.readUInt32LE(p)===1)aligns.push(Number(b.readBigUInt64LE(p+48)));}
      findings.push({file:name,loadAlignments:aligns,aligned16K:aligns.length>0&&aligns.every(x=>x>=16384)});
    } else if(depth<3) {
      for(const e of entries(b)) if(/(?:arm64-v8a|x86_64|libpython|libffmpeg)/.test(e.name)||depth>0)
        if(/\.so(?:\.[\d.]+)?$/.test(e.name)) scan(e.data(),name+'!'+e.name,depth+1);
    }
  }
  scan(fs.readFileSync(file),path.basename(file));
  console.log(JSON.stringify({artifact:path.basename(file),scope:'Static ELF PT_LOAD alignment only; device execution remains required',findings},null,2));
}
