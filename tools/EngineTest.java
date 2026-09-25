import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * 引擎逻辑 JVM 验证：复制 Lexicon/SyllableSplitter 逻辑做查询回归。
 * 运行: java EngineTest app/src/main/assets/lexicon/lex.bin
 */
public class EngineTest {

    static byte[][] keys;
    static int[] keyStart, keyCount;
    static byte[] entryData;
    static int[] entryOffsets;

    public static void main(String[] args) throws Exception {
        byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4]; buf.get(magic);
        if (magic[0]!='Q'||magic[1]!='J'||magic[2]!='L'||magic[3]!='1') throw new IOException("magic");
        int keyCountN = buf.getInt();
        keys = new byte[keyCountN][]; keyStart = new int[keyCountN]; keyCount = new int[keyCountN];
        for (int i=0;i<keyCountN;i++){
            int kl = buf.getShort()&0xFFFF; byte[] kb=new byte[kl]; buf.get(kb);
            keys[i]=kb; keyStart[i]=buf.getInt(); keyCount[i]=buf.getInt();
        }
        int entryN = buf.getInt();
        entryData = new byte[raw.length-buf.position()];
        buf.get(entryData);
        entryOffsets = new int[entryN+1];
        int p=0;
        for (int i=0;i<entryN;i++){
            entryOffsets[i]=p;
            int cl = readShort(p);
            p += 2+cl*2+4;
        }
        entryOffsets[entryN]=p;
        System.out.println("keys="+keyCountN+" entries="+entryN);

        int fail=0;
        // 1. 基础查询
        fail += check("nihao", "你好");
        fail += check("xiansheng", "先生");
        fail += check("shijian", "时间");
        fail += check("ni", "你");
        fail += check("hao", "好");
        // 2. 常用词存在性
        for (String[] t : new String[][]{{"zhongguo","中国"},{"jisuanji","计算机"},{"xuesheng","学生"},
                {"laoshi","老师"},{"dianhua","电话"},{"aomi","奥秘"},{"lvxing","旅行"},{"nver","女儿"}}) {
            List<String> top = topWords(t[0], 10);
            boolean hit = top.contains(t[1]);
            System.out.println((hit?"PASS":"FAIL") + " " + t[0] + " -> " + top.subList(0, Math.min(5, top.size())) + (hit?"":" (期望含 "+t[1]+")"));
            if (!hit) fail++;
        }
        // 3. 前缀预测: "xians" 应该能找到 先生
        {
            List<String> pref = prefixWords("xians", 5);
            System.out.println((pref.contains("先生")?"PASS":"FAIL") + " prefix xians -> " + pref);
            if (!pref.contains("先生")) fail++;
        }
        // 4. 查询性能
        {
            long t0=System.nanoTime();
            for (int i=0;i<1000;i++) topWords("qingjian", 20);
            long ms=(System.nanoTime()-t0)/1_000_000;
            System.out.println("PERF 1000x qingjian(无此key) = " + ms + "ms");
            t0=System.nanoTime();
            for (int i=0;i<1000;i++) topWords("shi", 20);
            ms=(System.nanoTime()-t0)/1_000_000;
            System.out.println("PERF 1000x shi = " + ms + "ms");
        }
        System.out.println(fail==0 ? "ALL PASS" : fail+" FAILURES");
        System.exit(fail==0?0:1);
    }

    static List<String> topWords(String key, int n) {
        List<String> out=new ArrayList<>();
        int idx = binarySearch(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (idx<0) return out;
        int s=keyStart[idx], c=Math.min(keyCount[idx], n);
        for (int i=0;i<c;i++){
            int e=entryOffsets[s+i];
            int cl=readShort(e);
            StringBuilder sb=new StringBuilder();
            for (int j=0;j<cl;j++) sb.append(readChar(e+2+j*2));
            out.add(sb.toString());
        }
        return out;
    }

    static List<String> prefixWords(String prefix, int perKey) {
        List<String> out=new ArrayList<>();
        int lo = lowerBound(prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (lo<0) return out;
        for (int i=lo;i<keys.length;i++){
            if (!startsWith(keys[i], prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) break;
            int s=keyStart[i], c=Math.min(keyCount[i], perKey);
            for (int j=0;j<c;j++){
                int e=entryOffsets[s+j];
                int cl=readShort(e);
                StringBuilder sb=new StringBuilder();
                for (int k=0;k<cl;k++) sb.append(readChar(e+2+k*2));
                out.add(sb.toString());
            }
            if (out.size()>50) break;
        }
        return out;
    }

    static int check(String key, String expectFirst) {
        List<String> top = topWords(key, 5);
        boolean ok = !top.isEmpty() && top.get(0).equals(expectFirst);
        System.out.println((ok?"PASS":"FAIL") + " " + key + " -> " + top);
        return ok?0:1;
    }

    // ---- 二进制读取（与 Lexicon.java 相同） ----
    static int binarySearch(byte[] key){
        int lo=0, hi=keys.length-1;
        while (lo<=hi){
            int mid=(lo+hi)>>>1;
            int cmp=compareBytes(keys[mid],key);
            if (cmp<0) lo=mid+1; else if (cmp>0) hi=mid-1; else return mid;
        }
        return -1;
    }
    static int lowerBound(byte[] p){
        int lo=0, hi=keys.length;
        while (lo<hi){
            int mid=(lo+hi)>>>1;
            if (comparePrefix(keys[mid],p)<0) lo=mid+1; else hi=mid;
        }
        return lo<keys.length?lo:-1;
    }
    static int compareBytes(byte[] a, byte[] b){
        int n=Math.min(a.length,b.length);
        for (int i=0;i<n;i++){int d=(a[i]&0xFF)-(b[i]&0xFF); if(d!=0) return d;}
        return a.length-b.length;
    }
    static int comparePrefix(byte[] a, byte[] p){
        int n=Math.min(a.length,p.length);
        for (int i=0;i<n;i++){int d=(a[i]&0xFF)-(p[i]&0xFF); if(d!=0) return d;}
        if (a.length==p.length) return 0;
        return a.length>p.length?0:1;
    }
    static boolean startsWith(byte[] a, byte[] p){
        if (a.length<p.length) return false;
        for (int i=0;i<p.length;i++) if (a[i]!=p[i]) return false;
        return true;
    }
    static int readShort(int off){ return (entryData[off]&0xFF)|((entryData[off+1]&0xFF)<<8); }
    static int readInt(int off){ return (entryData[off]&0xFF)|((entryData[off+1]&0xFF)<<8)|((entryData[off+2]&0xFF)<<16)|((entryData[off+3]&0xFF)<<24); }
    static char readChar(int off){ return (char)((entryData[off]&0xFF)|((entryData[off+1]&0xFF)<<8)); }
}
