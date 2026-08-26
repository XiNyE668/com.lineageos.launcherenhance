#!/usr/bin/env python3
import sys, zipfile, struct, hashlib, zlib, re
from pathlib import Path

def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def uleb(b,o):
    r=0;s=0
    while True:
        x=b[o];o+=1;r|=(x&0x7f)<<s
        if not x&0x80:return r,o
        s+=7

def read_str(b,o):
    _,o=uleb(b,o); end=b.index(0,o)
    return b[o:end].decode('utf-8','replace')

class Dex:
    def __init__(self,b):
        self.b=b
        self.string_ids_size=u32(b,56); self.string_ids_off=u32(b,60)
        self.type_ids_size=u32(b,64); self.type_ids_off=u32(b,68)
        self.method_ids_size=u32(b,88); self.method_ids_off=u32(b,92)
        self.class_defs_size=u32(b,96); self.class_defs_off=u32(b,100)
        self.strings=[read_str(b,u32(b,self.string_ids_off+4*i)) for i in range(self.string_ids_size)]
        self.types=[self.strings[u32(b,self.type_ids_off+4*i)] for i in range(self.type_ids_size)]
        self.methods=[]
        for i in range(self.method_ids_size):
            o=self.method_ids_off+8*i
            self.methods.append((self.types[u16(b,o)],self.strings[u32(b,o+4)],u16(b,o+2)))
        self.code_by_method={}
        for ci in range(self.class_defs_size):
            o=self.class_defs_off+32*ci; data=u32(b,o+24)
            if not data: continue
            q=data
            sf,q=uleb(b,q); inf,q=uleb(b,q); dm,q=uleb(b,q); vm,q=uleb(b,q)
            for _ in range(sf+inf): _,q=uleb(b,q); _,q=uleb(b,q)
            for count in (dm,vm):
                idx=0
                for _ in range(count):
                    diff,q=uleb(b,q); idx+=diff
                    _,q=uleb(b,q); code,q=uleb(b,q)
                    if code:self.code_by_method[idx]=code

def method_index(d, cls, name):
    hits=[i for i,(c,n,p) in enumerate(d.methods) if c==cls and n==name]
    if len(hits)!=1: raise RuntimeError((cls,name,hits))
    return hits[0]

def code_units(b,off):
    sz=u32(b,off+12)
    return list(struct.unpack_from('<'+'H'*sz,b,off+16))

def write_units(b,off,u,outs=None):
    sz=u32(b,off+12)
    if len(u)!=sz: raise RuntimeError(('size',len(u),sz,hex(off)))
    if outs is not None: struct.pack_into('<H',b,off+4,outs)
    struct.pack_into('<'+'H'*sz,b,off+16,*u)

def fix_dex_header(b):
    b[12:32]=hashlib.sha1(b[32:]).digest()
    struct.pack_into('<I',b,8,zlib.adler32(b[12:]) & 0xffffffff)

def read_uleb(data,p):
    v=0;s=0;start=p
    while True:
        x=data[p];p+=1;v|=(x&0x7f)<<s
        if x<0x80:return v,p,start
        s+=7

def read_sleb(data,p):
    v=0;s=0;start=p
    while True:
        x=data[p];p+=1;v|=(x&0x7f)<<s;s+=7
        if x<0x80:
            if x&0x40:v|=-(1<<s)
            return v,p,start

def enc_uleb_same_len(value,length):
    out=[];n=value
    while True:
        x=n&0x7f;n>>=7
        if n:x|=0x80
        out.append(x)
        if not n:break
    if len(out)!=length: raise RuntimeError(('uleb length',value,length,out))
    return bytes(out)

def patch_hookprovider_catch_throwable(b,d):
    mi=method_index(d,'Lcom/hhvvg/launcher/hook/HookProviderKt;','applyMethodHook')
    off=d.code_by_method[mi]; tries=u16(b,off+6); insns_size=u32(b,off+12)
    if tries<1: raise RuntimeError('applyMethodHook no catch')
    p=off+16+insns_size*2
    if insns_size&1:p+=2
    hlist=p+tries*8
    cnt,q,_=read_uleb(b,hlist)
    exc=d.types.index('Ljava/lang/Exception;'); thr=d.types.index('Ljava/lang/Throwable;')
    patched=0
    for _ in range(cnt):
        sz,q,_=read_sleb(b,q)
        for __ in range(abs(sz)):
            typ,q2,toff=read_uleb(b,q); _,q3,_=read_uleb(b,q2)
            if typ==exc:
                b[toff:q2]=enc_uleb_same_len(thr,q2-toff); patched+=1
            q=q3
        if sz<=0: _,q,_=read_uleb(b,q)
    if patched!=1: raise RuntimeError(('catch patch count',patched))

def restart_sequence(d):
    mypid=method_index(d,'Landroid/os/Process;','myPid')
    send=method_index(d,'Landroid/os/Process;','sendSignal')
    return [0x0071,mypid,0x0000, 0x000a, 0x0113,0x0009,
            0x2071,send,0x0010, 0x000e]

def patch_main(raw):
    b=bytearray(raw); d=Dex(bytes(b)); restart=restart_sequence(d)

    # Icon pack: preserve provider assignment + module icon-cache clear, then restart Trebuchet.
    mi=method_index(d,'Lcom/hhvvg/launcher/Launcher$LauncherCallback;','onIconPackProviderChanged')
    off=d.code_by_method[mi]; u=code_units(b,off)
    if len(u)<7+len(restart): raise RuntimeError('icon callback too small')
    u[7:7+len(restart)]=restart
    for i in range(7+len(restart),len(u)):u[i]=0
    write_units(b,off,u,outs=2)

    # Disable obsolete Android-13 DeviceProfile proxy; compiled LOS23 adapter replaces it.
    mi=method_index(d,'Lcom/hhvvg/launcher/hook/HookTargetsKt;','<clinit>')
    off=d.code_by_method[mi]; u=code_units(b,off)
    old=d.types.index('Lcom/hhvvg/launcher/DeviceProfile;')
    neutral=d.types.index('Lcom/hhvvg/launcher/component/Component;')
    pos=[i for i,x in enumerate(u) if x==old and i>0 and (u[i-1]&0xff)==0x1c]
    if len(pos)!=1: raise RuntimeError(('DeviceProfile target positions',pos))
    u[pos[0]]=neutral; write_units(b,off,u)

    # Setting changes are delivered on posted callbacks; restart once settings have landed.
    names=[
      'lambda$onAllAppsIconVisibilityChanged$8$com-hhvvg-launcher-Launcher$LauncherCallback',
      'lambda$onIconDrawablePaddingScaleChanged$7$com-hhvvg-launcher-Launcher$LauncherCallback',
      'lambda$onIconScaleChanged$5$com-hhvvg-launcher-Launcher$LauncherCallback',
      'lambda$onIconTextScaleChanged$6$com-hhvvg-launcher-Launcher$LauncherCallback',
      'lambda$onIconTextVisibilityChanged$2$com-hhvvg-launcher-Launcher$LauncherCallback',
      'lambda$onSetUseCustomSpringLoadedEffect$4$com-hhvvg-launcher-Launcher$LauncherCallback',
    ]
    for name in names:
        mi=method_index(d,'Lcom/hhvvg/launcher/Launcher$LauncherCallback;',name)
        off=d.code_by_method[mi]; u=code_units(b,off)
        u[:len(restart)]=restart
        for i in range(len(restart),len(u)):u[i]=0
        write_units(b,off,u,outs=2)

    # NoSuchMethodError/NoSuchFieldError are Error subclasses: isolate obsolete hooks on Android 16.
    patch_hookprovider_catch_throwable(b,d)
    fix_dex_header(b)
    return bytes(b)

def strip_signature(name):
    up=name.upper()
    return up=='META-INF/MANIFEST.MF' or bool(re.match(r'^META-INF/[^/]+\.(SF|RSA|DSA|EC)$',up))

def copy_info(info, force_stored=False):
    n=zipfile.ZipInfo(info.filename,date_time=info.date_time)
    n.compress_type=zipfile.ZIP_STORED if force_stored else info.compress_type
    n.external_attr=info.external_attr; n.internal_attr=info.internal_attr
    n.create_system=info.create_system; n.flag_bits=info.flag_bits; n.comment=info.comment
    return n

def rebuild(src,out,compat_dex):
    with zipfile.ZipFile(src,'r') as zin:
        main=patch_main(zin.read('classes.dex'))
        xinit=zin.read('assets/xposed_init').decode('utf-8').strip()
        xinit=(xinit+'\ncom.hhvvg.launcher.compat.Los23CompatHook\n').encode('utf-8')
        with zipfile.ZipFile(out,'w',allowZip64=True) as zout:
            for info in zin.infolist():
                name=info.filename
                if strip_signature(name): continue
                data=zin.read(name)
                if name=='classes.dex': data=main
                elif name=='assets/xposed_init': data=xinit
                force=name=='resources.arsc' or bool(re.fullmatch(r'classes\d*\.dex',name))
                zout.writestr(copy_info(info,force),data)
            ni=zipfile.ZipInfo('classes3.dex',date_time=(2026,1,1,0,0,0))
            ni.compress_type=zipfile.ZIP_STORED
            zout.writestr(ni,compat_dex)

def validate(path):
    with zipfile.ZipFile(path) as z:
        if z.testzip() is not None: raise RuntimeError('zip CRC failure')
        names=set(z.namelist())
        for n in ('classes.dex','classes2.dex','classes3.dex','resources.arsc','assets/xposed_init'):
            if n not in names: raise RuntimeError('missing '+n)
        if b'Los23CompatHook' not in z.read('assets/xposed_init'): raise RuntimeError('xposed init missing')
        for n in ('classes.dex','classes2.dex','classes3.dex','resources.arsc'):
            if z.getinfo(n).compress_type != zipfile.ZIP_STORED: raise RuntimeError(n+' compressed')
        for n in ('classes.dex','classes2.dex','classes3.dex'):
            d=z.read(n)
            if not d.startswith(b'dex\n'): raise RuntimeError(n+' magic')
            if hashlib.sha1(d[32:]).digest()!=d[12:32]: raise RuntimeError(n+' sha1')
            if (zlib.adler32(d[12:])&0xffffffff)!=u32(d,8): raise RuntimeError(n+' adler')

if __name__=='__main__':
    if len(sys.argv)!=4:
        print('usage: patch_apk.py original.apk compat.dex output.apk',file=sys.stderr);sys.exit(2)
    src,compat,out=map(Path,sys.argv[1:])
    rebuild(src,out,compat.read_bytes()); validate(out)
    print(out)
