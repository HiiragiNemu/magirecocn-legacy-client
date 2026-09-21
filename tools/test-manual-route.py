import argparse,hashlib,http.server,json,os,random,re,subprocess,threading,time,zipfile
from pathlib import Path
R=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description="Real HTTP tests for player-driven route switching")
parser.add_argument('--classes',type=Path,required=True)
parser.add_argument('--classpath',required=True)
parser.add_argument('--out',type=Path,default=R/'.build/manual-route-tests')
args=parser.parse_args()
D=args.out.resolve();D.mkdir(parents=True,exist_ok=True)
fixture=D/'fixture.zip'
if not fixture.exists():
 with zipfile.ZipFile(fixture,'w',compression=zipfile.ZIP_STORED) as z:z.writestr('fixture.bin',random.Random(831).randbytes(2*1024*1024))
DATA=fixture.read_bytes();requests=[]
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_HEAD(self):self.serve(False)
 def do_GET(self):self.serve(True)
 def serve(self,body):
  start,end=0,len(DATA)-1;status=200
  r=self.headers.get('Range')
  if r and not self.path.startswith('/norange/'):
   m=re.fullmatch(r'bytes=(\d+)-(\d*)',r)
   if not m:self.send_error(400);return
   start=int(m[1]);end=min(end,int(m[2]) if m[2] else end);status=206
  requests.append(dict(path=self.path,method=self.command,start=start,end=end,range=r,if_range=self.headers.get('If-Range')))
  self.send_response(status);self.send_header('Content-Length',str(end-start+1));self.send_header('Accept-Ranges','bytes')
  self.send_header('ETag','"'+self.path.split('/')[1]+'-etag"')
  if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{len(DATA)}')
  self.end_headers()
  if body:
   try:
    for offset in range(start,end+1,4096):
     self.wfile.write(DATA[offset:min(end+1,offset+4096)]);self.wfile.flush();time.sleep(.012)
   except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_address[1]
testclasses=D/'test-classes';testclasses.mkdir(exist_ok=True)
cp=os.pathsep.join([str(testclasses),str(args.classes.resolve()),args.classpath])
logs=[]
def run(args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace',cwd=R)
 logs.append(dict(command=list(map(str,args)),exit=p.returncode,stdout=p.stdout,stderr=p.stderr))
 (D/'http-test-verification.json').write_text(json.dumps(logs,ensure_ascii=False,indent=2),encoding='utf-8')
 print(p.stdout[-3000:],p.stderr[-2000:],flush=True)
 if p.returncode:raise SystemExit(p.returncode)
try:
 if os.name=='nt':
  run(['javac','-encoding','UTF-8','-source','8','-target','8','-d',testclasses,R/'tools/teststubs/android/system/Os.java'])
 run(['javac','-encoding','UTF-8','-source','8','-target','8','-cp',cp,'-d',testclasses,R/'tools/ManualRouteIntegrationTest.java'])
 out=D/('http-output-'+str(time.time_ns()));out.mkdir()
 run(['java','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',cp,'io.kamihama.magianative.ManualRouteIntegrationTest',f'http://127.0.0.1:{port}/a/',f'http://127.0.0.1:{port}/b/',out,fixture,hashlib.md5(DATA).hexdigest()])
 assert any(r['method']=='GET' and r['path'].startswith('/b/') and 'cnv_hot=73-' in r['path'] and r['start']>0 for r in requests),'full hot-update did not resume from a nonzero offset on B'
 print('PASS: observed production hot-update B HTTP Range request starts after retained bytes',flush=True)
finally:
 server.shutdown();server.server_close()
 (D/'http-requests.json').write_text(json.dumps(requests,indent=2),encoding='utf-8')
