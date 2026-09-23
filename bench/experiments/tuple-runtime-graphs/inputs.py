#!/usr/bin/env python3
import hashlib,json,pathlib,subprocess,sys
out,runtime,module,oracle=map(pathlib.Path,sys.argv[1:])
here=pathlib.Path(__file__).resolve().parent
files=[module,oracle,here/'TupleRuntimeGraphProbe.java',*sorted((runtime/'build/install/thc/lib').glob('thc-*.jar'))]
manifest={'runtimeCheckoutHeadAtLaunch':subprocess.check_output(['git','-C',str(runtime),'rev-parse','HEAD'],text=True).strip(),
          'inputsSha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(out/'run-inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
