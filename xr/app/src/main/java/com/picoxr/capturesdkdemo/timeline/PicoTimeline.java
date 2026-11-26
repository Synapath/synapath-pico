package com.picoxr.capturesdkdemo.timeline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only inspection of PXR's existing file timeline. Never rewrites MP4 data. */
public final class PicoTimeline {
    private static final long MICRO = 1_000_000L;

    private static final class Box {
        final String type;
        final long start, end;
        Box(String type, long start, long end) {
            this.type = type; this.start = start; this.end = end;
        }
    }

    public static final class Track {
        public final String kind;
        public final long count, baseUs, firstPtsUs, lastPtsUs;
        Track(String kind, long count, long baseUs, long first, long last) {
            this.kind = kind; this.count = count; this.baseUs = baseUs;
            firstPtsUs = first; lastPtsUs = last;
        }
        String json() {
            return "{\"kind\":\"" + kind + "\",\"samples\":" + count
                    + ",\"vendor_tbtm_us\":" + baseUs
                    + ",\"first_pts_us\":" + firstPtsUs
                    + ",\"last_pts_us\":" + lastPtsUs + "}";
        }
    }

    public static final class Report {
        public final List<Track> tracks;
        public final boolean sharedTimebase;
        public final long baseUs;
        Report(List<Track> tracks) {
            this.tracks = Collections.unmodifiableList(tracks);
            Set<String> present = new HashSet<>();
            long base = tracks.isEmpty() ? -1 : tracks.get(0).baseUs;
            boolean matching = base > 0;
            for (Track track : tracks) {
                present.add(track.kind);
                matching &= track.baseUs == base && track.count > 0;
            }
            sharedTimebase = matching && tracks.size() == 3
                    && present.containsAll(Arrays.asList("rgb", "pose", "depth"));
            baseUs = sharedTimebase ? base : -1;
        }
        public long samples(String kind) {
            for (Track track : tracks) if (track.kind.equals(kind)) return track.count;
            return 0;
        }
        public String json() {
            StringJoiner rows = new StringJoiner(",");
            for (Track track : tracks) rows.add(track.json());
            return "{\"schema\":2,\"method\":\"preserve_pxr_timeline\","
                    + "\"shared_timebase_verified\":" + sharedTimebase
                    + ",\"vendor_tbtm_us\":" + baseUs
                    + ",\"pts_shift_us\":0,\"trimmed_samples\":0,"
                    + "\"sensor_latency_calibrated\":false,\"tracks\":[" + rows + "]}";
        }
    }

    private static void check(boolean valid, String reason) throws IOException {
        if (!valid) throw new IOException(reason);
    }
    private static long u32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(input.readInt());
    }
    private static List<Box> boxes(RandomAccessFile input, long start, long end) throws IOException {
        List<Box> result = new ArrayList<>();
        while (start < end) {
            check(end - start >= 8, "Truncated MP4 box");
            input.seek(start);
            long size = u32(input);
            byte[] type = new byte[4]; input.readFully(type);
            int header = 8;
            if (size == 1) { size = input.readLong(); header = 16; }
            if (size == 0) size = end - start;
            check(size >= header && size <= end - start, "Invalid MP4 box extent");
            result.add(new Box(new String(type, StandardCharsets.ISO_8859_1), start + header, start + size));
            start += size;
        }
        return result;
    }
    private static Box child(RandomAccessFile input, Box parent, String name) throws IOException {
        for (Box box : boxes(input, parent.start, parent.end)) if (box.type.equals(name)) return box;
        return null;
    }
    private static Box required(RandomAccessFile input, Box parent, String name) throws IOException {
        Box box = child(input, parent, name);
        check(box != null, "Missing MP4 " + name);
        return box;
    }
    private static int version(RandomAccessFile input, Box box) throws IOException {
        input.seek(box.start); int version = input.readUnsignedByte();
        check(version <= 1, "Unsupported full-box version"); return version;
    }
    private static long scale(RandomAccessFile input, Box box) throws IOException {
        int version = version(input, box);
        input.seek(box.start + (version == 1 ? 20 : 12));
        long scale = u32(input); check(scale > 0, "Invalid timescale"); return scale;
    }
    private static long micros(long ticks, long scale) {
        return Math.round(ticks * (double) MICRO / scale);
    }
    private static long entries(RandomAccessFile input, Box box, int stride) throws IOException {
        input.seek(box.start + 4); long count = u32(input);
        check(count <= (box.end - box.start - 8) / stride, "Truncated MP4 table");
        return count;
    }
    private static Track track(RandomAccessFile input, Box box, long movieScale) throws IOException {
        Box media = required(input, box, "mdia");
        long scale = scale(input, required(input, media, "mdhd"));
        Box table = required(input, required(input, media, "minf"), "stbl");
        Box description = required(input, table, "stsd");
        check(description.end - description.start <= 4 * 1024 * 1024, "Oversized sample description");
        byte[] desc = new byte[(int) (description.end - description.start)];
        input.seek(description.start); input.readFully(desc);
        check(desc.length >= 16, "Truncated sample description");
        String format = new String(desc, 12, 4, StandardCharsets.ISO_8859_1);
        String text = new String(desc, StandardCharsets.ISO_8859_1);
        String kind = format.equals("hvc1") || format.equals("avc1") ? "rgb"
                : format.equals("raw1") ? "depth"
                : text.contains("application/pose") && text.contains("head") ? "pose" : null;
        if (kind == null) return null;
        long base = -1;
        for (int i = 4; i + 12 <= desc.length; i++) {
            if (desc[i-4] == 0 && desc[i-3] == 0 && desc[i-2] == 0 && desc[i-1] == 16
                    && desc[i] == 't' && desc[i+1] == 'b' && desc[i+2] == 't' && desc[i+3] == 'm') {
                base = 0;
                for (int j = 4; j < 12; j++) base = (base << 8) | (desc[i+j] & 255L);
                break;
            }
        }
        long count = 0, dts = 0, lastDts = 0;
        Box stts = required(input, table, "stts");
        long runs = entries(input, stts, 8);
        for (long i = 0; i < runs; i++) {
            long n = u32(input), duration = u32(input);
            check(n > 0, "Empty timing run");
            lastDts = dts + (n - 1) * duration;
            dts += n * duration; count += n;
        }
        Box stsz = required(input, table, "stsz");
        input.seek(stsz.start + 8);
        check(u32(input) == count, "Sample count mismatch");
        long firstCts = 0, lastCts = 0;
        Box ctts = child(input, table, "ctts");
        if (ctts != null) {
            int version = version(input, ctts);
            runs = entries(input, ctts, 8); long seen = 0;
            for (long i = 0; i < runs; i++) {
                long n = u32(input), offset = version == 1 ? input.readInt() : u32(input);
                if (seen == 0) firstCts = offset;
                seen += n; lastCts = offset;
            }
            check(seen == count, "Composition count mismatch");
        }
        long delay = 0, mediaStart = 0;
        Box edits = child(input, box, "edts");
        if (edits != null) {
            Box elst = required(input, edits, "elst"); int version = version(input, elst);
            runs = entries(input, elst, version == 1 ? 20 : 12); boolean active = false;
            for (long i = 0; i < runs; i++) {
                long length = version == 1 ? input.readLong() : u32(input);
                long mediaTime = version == 1 ? input.readLong() : input.readInt();
                check(input.readInt() == 65536, "Unsupported edit playback rate");
                if (mediaTime == -1) { check(!active, "Trailing edit unsupported"); delay += length; }
                else { check(!active, "Multiple active edits unsupported"); active = true; mediaStart = mediaTime; }
            }
        }
        long offset = micros(delay, movieScale) - micros(mediaStart, scale);
        return new Track(kind, count, base, micros(firstCts, scale) + offset,
                micros(lastDts + lastCts, scale) + offset);
    }

    public static Report inspect(File source) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            Box root = new Box("root", 0, input.length());
            Box movie = required(input, root, "moov");
            long movieScale = scale(input, required(input, movie, "mvhd"));
            List<Track> tracks = new ArrayList<>();
            for (Box box : boxes(input, movie.start, movie.end)) if (box.type.equals("trak")) {
                Track track = track(input, box, movieScale);
                if (track != null) tracks.add(track);
            }
            return new Report(tracks);
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    /** Caller owns output. Hash describes precisely the original bytes copied. */
    public static String copyUnchanged(File source, OutputStream output) throws IOException {
        MessageDigest hash = digest(); long count = 0, length = source.length();
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[256 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n); hash.update(buffer, 0, n); count += n;
            }
        }
        output.flush();
        check(count > 0 && count == length && source.length() == length, "Incomplete/changing SDK recording");
        return hex(hash.digest());
    }
    public static String sha256(InputStream input) throws IOException {
        MessageDigest hash = digest(); byte[] buffer = new byte[256 * 1024]; int n;
        while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
        return hex(hash.digest());
    }
    public static void main(String[] args) throws Exception {
        File source = new File(args[0]); Report report = inspect(source);
        if (args.length > 1) {
            File output = new File(args[1]); check(output.createNewFile(), "Output exists");
            String hash;
            try (OutputStream stream = new FileOutputStream(output)) { hash = copyUnchanged(source, stream); }
            try (InputStream stream = new FileInputStream(output)) {
                check(hash.equals(sha256(stream)), "Published copy hash mismatch");
            }
        }
        System.out.println(report.json());
    }
}
