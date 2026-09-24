#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Record the installed runtime, checkout, and probe used for this run."""
import hashlib,json,pathlib,subprocess,sys
out,runtime=map(pathlib.Path,sys.argv[1:])
here=pathlib.Path(__file__).resolve().parent
files=[*sorted(here.glob('*.java')),*sorted(here.glob('*.py')),*sorted(here.glob('*.sh')),
       *sorted((runtime/'build/install/thc/lib').glob('*.jar'))]
result={'runtimeCheckoutHeadAtLaunch':subprocess.check_output(['git','-C',str(runtime),'rev-parse','HEAD'],text=True).strip(),
        'inputsSha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(out/'run-inputs.json').write_text(json.dumps(result,indent=2)+'\n')
