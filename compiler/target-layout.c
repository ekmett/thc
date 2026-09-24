// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

// A receipt for the layout used by GHC 9.14.1's unmodified stack decoder.
// It is compiled against the same installed headers as the pinned .hsc files.
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "Rts.h"
#undef BLOCK_SIZE
#undef MBLOCK_SIZE
#undef BLOCKS_PER_MBLOCK
#include "DerivedConstants.h"
#include "rts/IPE.h"

#if defined(__aarch64__) || defined(__arm64__)
#define THC_ARCH "aarch64"
#elif defined(__x86_64__)
#define THC_ARCH "x86_64"
#else
#error Unsupported GHC target architecture for stack layout
#endif

#if defined(__APPLE__)
#define THC_OS "osx"
#elif defined(__linux__)
#define THC_OS "linux"
#else
#error Unsupported GHC target OS for stack layout
#endif

#define NUMBER(name, value) printf("\"" name "\":%zu,", (size_t) (value))
#define MEMBER_BYTES(type, member) sizeof(((type *) 0)->member)

int main(void) {
    const uint16_t endian_probe = 1;
    putchar('{');
    NUMBER("schema", 1);
    NUMBER("wordBytes", sizeof(void *));
    printf("\"targetPlatform\":\"%s-%s\",", THC_ARCH, THC_OS);
    printf("\"endianness\":\"%s\",", *(const unsigned char *) &endian_probe == 1 ? "little" : "big");
    NUMBER("infoTableBytes", sizeof(struct StgInfoTable_));
    NUMBER("infoTablePtrsOffset", offsetof(struct StgInfoTable_, layout.payload.ptrs));
    NUMBER("infoTablePtrsBytes", MEMBER_BYTES(struct StgInfoTable_, layout.payload.ptrs));
    NUMBER("infoTableNptrsOffset", offsetof(struct StgInfoTable_, layout.payload.nptrs));
    NUMBER("infoTableNptrsBytes", MEMBER_BYTES(struct StgInfoTable_, layout.payload.nptrs));
    NUMBER("infoTableTypeOffset", offsetof(struct StgInfoTable_, type));
    NUMBER("infoTableTypeBytes", MEMBER_BYTES(struct StgInfoTable_, type));
    NUMBER("infoTableSrtOffset", offsetof(struct StgInfoTable_, srt));
    NUMBER("infoTableSrtBytes", MEMBER_BYTES(struct StgInfoTable_, srt));
    NUMBER("infoProvEntBytes", sizeof(InfoProvEnt));
    NUMBER("infoProvBytes", sizeof(InfoProv));
    NUMBER("infoProvEntInfoOffset", offsetof(InfoProvEnt, info));
    NUMBER("infoProvEntProvOffset", offsetof(InfoProvEnt, prov));
    NUMBER("infoProvNameOffset", offsetof(InfoProv, table_name));
    NUMBER("infoProvDescOffset", offsetof(InfoProv, closure_desc));
    NUMBER("infoProvDescBytes", MEMBER_BYTES(InfoProv, closure_desc));
    NUMBER("infoProvTyDescOffset", offsetof(InfoProv, ty_desc));
    NUMBER("infoProvLabelOffset", offsetof(InfoProv, label));
    NUMBER("infoProvUnitOffset", offsetof(InfoProv, unit_id));
    NUMBER("infoProvModuleOffset", offsetof(InfoProv, module));
    NUMBER("infoProvFileOffset", offsetof(InfoProv, src_file));
    NUMBER("infoProvSpanOffset", offsetof(InfoProv, src_span));
    NUMBER("closureRetBco", RET_BCO);
    NUMBER("closureRetSmall", RET_SMALL);
    NUMBER("closureRetBig", RET_BIG);
    NUMBER("closureRetFun", RET_FUN);
    NUMBER("closureUpdateFrame", UPDATE_FRAME);
    NUMBER("closureCatchFrame", CATCH_FRAME);
    NUMBER("closureUnderflowFrame", UNDERFLOW_FRAME);
    NUMBER("closureStopFrame", STOP_FRAME);
    NUMBER("closureStack", STACK);
    NUMBER("closureAtomicallyFrame", ATOMICALLY_FRAME);
    NUMBER("closureCatchRetryFrame", CATCH_RETRY_FRAME);
    NUMBER("closureCatchStmFrame", CATCH_STM_FRAME);
    NUMBER("closureAnnFrame", ANN_FRAME);
    NUMBER("stackHeaderBytes", sizeof(StgHeader));
    NUMBER("stackCatchHandlerBytes", OFFSET_StgCatchFrame_handler + sizeof(StgHeader));
    NUMBER("stackCatchFrameBytes", SIZEOF_StgCatchFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackCatchStmCodeBytes", OFFSET_StgCatchSTMFrame_code + sizeof(StgHeader));
    NUMBER("stackCatchStmHandlerBytes", OFFSET_StgCatchSTMFrame_handler + sizeof(StgHeader));
    NUMBER("stackCatchStmFrameBytes", SIZEOF_StgCatchSTMFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackUpdateeBytes", OFFSET_StgUpdateFrame_updatee + sizeof(StgHeader));
    NUMBER("stackUpdateFrameBytes", SIZEOF_StgUpdateFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackAtomicallyCodeBytes", OFFSET_StgAtomicallyFrame_code + sizeof(StgHeader));
    NUMBER("stackAtomicallyResultBytes", OFFSET_StgAtomicallyFrame_result + sizeof(StgHeader));
    NUMBER("stackAtomicallyFrameBytes", SIZEOF_StgAtomicallyFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackCatchRetryAltCodeBytes", OFFSET_StgCatchRetryFrame_running_alt_code + sizeof(StgHeader));
    NUMBER("stackCatchRetryFirstCodeBytes", OFFSET_StgCatchRetryFrame_first_code + sizeof(StgHeader));
    NUMBER("stackCatchRetryAltBytes", OFFSET_StgCatchRetryFrame_alt_code + sizeof(StgHeader));
    NUMBER("stackCatchRetryFrameBytes", SIZEOF_StgCatchRetryFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackRetFunSizeBytes", OFFSET_StgRetFun_size);
    NUMBER("stackRetFunFunBytes", OFFSET_StgRetFun_fun);
    NUMBER("stackRetFunPayloadBytes", OFFSET_StgRetFun_payload);
    NUMBER("stackRetFunFrameBytes", SIZEOF_StgRetFun);
    NUMBER("stackAnnPayloadBytes", OFFSET_StgAnnFrame_ann + sizeof(StgHeader));
    NUMBER("stackAnnFrameBytes", SIZEOF_StgAnnFrame_NoHdr + sizeof(StgHeader));
    NUMBER("stackClosurePayloadBytes", OFFSET_StgClosure_payload + sizeof(StgHeader));
    printf("\"profiled\":false}\n");
    return 0;
}
