from pathlib import Path
import os,subprocess,json,shutil,xml.etree.ElementTree as E
root=Path.cwd();out=root/'work/defaults-validation';javaHome='/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home'
def capture(name):
 src=root/'build/test-results/test';dest=out/(name+'-xml');dest.mkdir()
 for p in src.glob('TEST-*.xml'):shutil.copy2(p,dest/p.name)
 rs=[E.parse(p).getroot() for p in dest.glob('TEST-*.xml')]
 return {k:sum(int(r.get(k,0)) for r in rs) for k in ('tests','failures','errors','skipped')}
records=[{'name':'default','argv':['scripts/gradle.sh','--no-daemon','test','installDist'],'JAVA_TOOL_OPTIONS':[],'returncode':0,'totals':capture('default')}]
assert records[0]['totals']==dict(tests=167,failures=0,errors=0,skipped=0)
for name,extra,flags,expected in [('owned-off',[],['-Dthc.classOwnedLayouts=false'],167),('headers-off-focused',['-Pthc.compactObjectHeaders=false','--tests','thc.runtime.ClassOwnedLayoutTest'],[],5)]:
 env=os.environ.copy();env.update(JAVA_HOME=javaHome,THC_GRADLE_USER_HOME='/Users/ekmett/thc/.gradle-user-home');env.pop('JAVA_TOOL_OPTIONS',None)
 if flags:env['JAVA_TOOL_OPTIONS']=' '.join(flags)
 argv=['scripts/gradle.sh','--no-daemon','test','--rerun']+extra
 with (out/(name+'.log')).open('w') as log:p=subprocess.run(argv,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
 record={'name':name,'argv':argv,'JAVA_TOOL_OPTIONS':flags,'returncode':p.returncode,'totals':capture(name)};records.append(record)
 (out/'tests.json').write_text(json.dumps(records,indent=2)+'\n');print(record,flush=True)
 assert p.returncode==0 and record['totals']==dict(tests=expected,failures=0,errors=0,skipped=0)
app=[]
for name,extra,expected in [('app-default','',True),('app-explicit-header-off','-XX:-UseCompactObjectHeaders ',False)]:
 env=os.environ.copy();env.update(JAVA_HOME=javaHome,JAVA_OPTS=extra+'-XX:+PrintFlagsFinal -version');env.pop('JAVA_TOOL_OPTIONS',None)
 argv=[str(root/'build/install/thc/bin/thc')]
 with (out/(name+'.log')).open('w') as log:p=subprocess.run(argv,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=20)
 lines=(out/(name+'.log')).read_text().splitlines();actual=[line for line in lines if ' UseCompactObjectHeaders ' in line]
 assert p.returncode==0 and len(actual)==1 and ('= true ' if expected else '= false ') in actual[0],actual
 app.append({'name':name,'argv':argv,'JAVA_OPTS':env['JAVA_OPTS'],'returncode':p.returncode,'flag':actual[0]})
(out/'app-flags.json').write_text(json.dumps(app,indent=2)+'\n');print('ALL CONTROLS PASSED',flush=True)
