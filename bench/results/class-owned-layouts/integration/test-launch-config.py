from pathlib import Path
import runpy,tempfile,json
module=runpy.run_path('tools/ci-performance.py');full=module['full_tests'];check=module['check_configuration'];g=full.__globals__;captured=[]
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);out=root/'output';out.mkdir();(out/'run.json').write_text(json.dumps({'repository':str(root)}))
 def fake_run(out,argv,name,timeout,env=None):
  captured.append({'argv':list(map(str,argv)),'name':name,'env':env})
  if name.endswith('-full-tests'):
   result=root/'build/test-results/test';result.mkdir(parents=True,exist_ok=True)
   (result/'TEST-fake.xml').write_text('<testsuite tests="167" failures="0" errors="0" skipped="0"/>')
  else:
   p=out/(name+'.log')
   p.write_text(''.join(f'VERIFIED_MAP\t{phase}\t{i}\t{i+1}\n' for phase in ['before-requested-compilation','after-requested-compilation'] for i in range(18))+'MAP_DIAGNOSTICS '+json.dumps({'backend':'bytecode','unsupportedTraps':0,'sourceNotesEnabled':True,'sourceRootCount':1})+'\n')
   return p
 g['run']=fake_run;g['verify']=lambda out:None
 for i,(flags,expected) in enumerate([([],True),(['-XX:-UseCompactObjectHeaders'],False),(['-XX:-UseCompactObjectHeaders','-XX:+UseCompactObjectHeaders'],True)]):
  assert full(out,str(i),flags)['tests']==167
  assert '-Pthc.compactObjectHeaders='+str(expected).lower() in captured[-1]['argv']
 mapdir=out/'frozen/map';mapdir.mkdir(parents=True);(mapdir/'oracle.tsv').write_text(''.join(f'mapAggregate\t{i}\t{i+1}\n' for i in range(18)))
 g['suite_config']=lambda out:{'configurations':{'plain':('current',[]),'explicit':('current',['-XX:-UseCompactObjectHeaders'])}}
 for name,override,expected in [('plain',None,'+'),('explicit',None,'-'),('plain',False,'-')]:
  check(out,Path('/fake/java'),name,compact_control=override)
  flags=[s for s in captured[-1]['argv'] if s.endswith('UseCompactObjectHeaders')]
  assert flags[-1]=='-XX:'+expected+'UseCompactObjectHeaders',flags
 assert captured[-1]['name']=='check-plain-compact-off'
Path('work/defaults-validation/launch-mock-results.json').write_text(json.dumps({'passed':True,'calls':captured},indent=2)+'\n')
print('PASS: explicit controls agree between full-test Gradle configuration and Map JVM commands')
