#include "id3conv.hpp"
#include "servicefilter.hpp"
#include "util.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr int kServiceId = 101;
constexpr int kPmtPid = 0x1000;
constexpr int kVideoPid = 0x0200;
constexpr int kAudio1Pid = 0x0201;
constexpr int kAudio2Pid = 0x0202;
constexpr int kCaptionPid = 0x0230;
constexpr int kSuperimposePid = 0x0238;
constexpr int kOtherPrivatePid = 0x0240;

struct Stream {
    uint8_t type;
    int pid;
    int componentTag;
};

struct PrivSample {
    int64_t pts;
    bool dataAligned;
    size_t id3PayloadOffset;
    std::string owner;
    std::vector<uint8_t> payload;
};

void require(bool condition, const char* message)
{
    if (!condition) {
        std::cerr << "FAIL: " << message << std::endl;
        std::exit(1);
    }
}

void appendCrc(std::vector<uint8_t>& section)
{
    uint32_t crc = calc_crc32(section.data(), static_cast<int>(section.size()));
    section.push_back(static_cast<uint8_t>(crc >> 24));
    section.push_back(static_cast<uint8_t>(crc >> 16));
    section.push_back(static_cast<uint8_t>(crc >> 8));
    section.push_back(static_cast<uint8_t>(crc));
}

std::vector<uint8_t> makePat()
{
    std::vector<uint8_t> section = {
        0x00, 0xb0, 0x00,
        0x00, 0x01,
        0xc1, 0x00, 0x00,
        static_cast<uint8_t>(kServiceId >> 8), static_cast<uint8_t>(kServiceId),
        static_cast<uint8_t>(0xe0 | (kPmtPid >> 8)), static_cast<uint8_t>(kPmtPid),
    };
    const int sectionLength = static_cast<int>(section.size()) - 3 + 4;
    section[1] = static_cast<uint8_t>(0xb0 | (sectionLength >> 8));
    section[2] = static_cast<uint8_t>(sectionLength);
    appendCrc(section);
    return section;
}

std::vector<uint8_t> makePmt()
{
    const Stream streams[] = {
        {0x02, kVideoPid, 0x00},
        {0x04, kAudio1Pid, 0x10},
        {0x04, kAudio2Pid, 0x11},
        {0x06, kCaptionPid, 0x30},
        {0x06, kSuperimposePid, 0x38},
        {0x06, kOtherPrivatePid, 0x40},
    };
    std::vector<uint8_t> section = {
        0x02, 0xb0, 0x00,
        static_cast<uint8_t>(kServiceId >> 8), static_cast<uint8_t>(kServiceId),
        0xc1, 0x00, 0x00,
        static_cast<uint8_t>(0xe0 | (kVideoPid >> 8)), static_cast<uint8_t>(kVideoPid),
        0xf0, 0x00,
    };
    for (const Stream& stream : streams) {
        section.push_back(stream.type);
        section.push_back(static_cast<uint8_t>(0xe0 | (stream.pid >> 8)));
        section.push_back(static_cast<uint8_t>(stream.pid));
        section.push_back(0xf0);
        section.push_back(3);
        section.push_back(0x52);
        section.push_back(1);
        section.push_back(static_cast<uint8_t>(stream.componentTag));
    }
    const int sectionLength = static_cast<int>(section.size()) - 3 + 4;
    section[1] = static_cast<uint8_t>(0xb0 | (sectionLength >> 8));
    section[2] = static_cast<uint8_t>(sectionLength);
    appendCrc(section);
    return section;
}

std::vector<uint8_t> makeTsPacket(int pid, bool unitStart, const std::vector<uint8_t>& payload, uint8_t counter = 0)
{
    require(payload.size() <= 184, "test payload exceeds one TS packet");
    std::vector<uint8_t> packet(188, 0xff);
    packet[0] = 0x47;
    packet[1] = static_cast<uint8_t>((unitStart ? 0x40 : 0) | ((pid >> 8) & 0x1f));
    packet[2] = static_cast<uint8_t>(pid);
    if (payload.size() == 184) {
        packet[3] = static_cast<uint8_t>(0x10 | (counter & 0x0f));
        std::copy(payload.begin(), payload.end(), packet.begin() + 4);
    } else {
        packet[3] = static_cast<uint8_t>(0x30 | (counter & 0x0f));
        const size_t adaptationLength = 183 - payload.size();
        packet[4] = static_cast<uint8_t>(adaptationLength);
        if (adaptationLength > 0) packet[5] = 0x00;
        std::copy(payload.begin(), payload.end(), packet.end() - payload.size());
    }
    return packet;
}

std::vector<uint8_t> makePsiPacket(int pid, const std::vector<uint8_t>& section)
{
    std::vector<uint8_t> payload(1, 0x00);
    payload.insert(payload.end(), section.begin(), section.end());
    return makeTsPacket(pid, true, payload);
}

std::vector<uint8_t> encodePts(int64_t pts)
{
    return {
        static_cast<uint8_t>(0x21 | ((pts >> 29) & 0x0e)),
        static_cast<uint8_t>(pts >> 22),
        static_cast<uint8_t>(0x01 | ((pts >> 14) & 0xfe)),
        static_cast<uint8_t>(pts >> 7),
        static_cast<uint8_t>(0x01 | ((pts << 1) & 0xfe)),
    };
}

std::vector<uint8_t> makePes(uint8_t streamId, int64_t pts, const std::vector<uint8_t>& payload)
{
    std::vector<uint8_t> pes = {0x00, 0x00, 0x01, streamId, 0x00, 0x00, 0x80, 0x80, 0x05};
    const std::vector<uint8_t> encodedPts = encodePts(pts);
    pes.insert(pes.end(), encodedPts.begin(), encodedPts.end());
    pes.insert(pes.end(), payload.begin(), payload.end());
    const size_t pesLength = pes.size() - 6;
    pes[4] = static_cast<uint8_t>(pesLength >> 8);
    pes[5] = static_cast<uint8_t>(pesLength);
    return pes;
}

const uint8_t* payloadStart(const uint8_t* packet)
{
    return packet + 188 - get_ts_payload_size(packet);
}

std::vector<Stream> parsePmtStreams(const std::vector<uint8_t>& packets, int pid)
{
    PSI psi = {};
    for (size_t offset = 0; offset + 188 <= packets.size(); offset += 188) {
        const uint8_t* packet = packets.data() + offset;
        if (extract_ts_header_pid(packet) != pid) continue;
        const int payloadSize = get_ts_payload_size(packet);
        extract_psi(
            &psi,
            payloadStart(packet),
            payloadSize,
            extract_ts_header_unit_start(packet),
            extract_ts_header_counter(packet));
        if (!psi.version_number || psi.table_id != 2) continue;
        const uint8_t* table = psi.data;
        int pos = 12 + (((table[10] & 0x03) << 8) | table[11]);
        const int end = 3 + psi.section_length - 4;
        std::vector<Stream> streams;
        while (pos + 4 < end) {
            const int infoLength = ((table[pos + 3] & 0x03) << 8) | table[pos + 4];
            streams.push_back({
                table[pos],
                ((table[pos + 1] & 0x1f) << 8) | table[pos + 2],
                -1,
            });
            pos += 5 + infoLength;
        }
        return streams;
    }
    return {};
}

bool hasStream(const std::vector<Stream>& streams, uint8_t type, int pid)
{
    return std::any_of(streams.begin(), streams.end(), [=](const Stream& stream) {
        return stream.type == type && stream.pid == pid;
    });
}

int metadataPid(const std::vector<Stream>& streams)
{
    const auto it = std::find_if(streams.begin(), streams.end(), [](const Stream& stream) {
        return stream.type == 0x15;
    });
    return it == streams.end() ? 0 : it->pid;
}

int synchsafe(const uint8_t* data)
{
    return (data[0] << 21) | (data[1] << 14) | (data[2] << 7) | data[3];
}

std::vector<PrivSample> parsePrivSamples(const std::vector<uint8_t>& packets, int pid)
{
    std::vector<PrivSample> samples;
    for (size_t offset = 0; offset + 188 <= packets.size(); offset += 188) {
        const uint8_t* packet = packets.data() + offset;
        if (extract_ts_header_pid(packet) != pid || !extract_ts_header_unit_start(packet)) continue;
        const uint8_t* pes = payloadStart(packet);
        const size_t available = static_cast<size_t>(packets.data() + offset + 188 - pes);
        if (available < 34 || pes[0] != 0 || pes[1] != 0 || pes[2] != 1 || pes[3] != 0xbd) continue;
        require(pes[6] == 0x84, "ID3 PES flags must set data_alignment_indicator (0x84)");
        require(pes[7] == 0x80, "ID3 PES must retain PTS-only flags");
        const int64_t pts = (static_cast<int64_t>(pes[9] & 0x0e) << 29) |
            (static_cast<int64_t>(pes[10]) << 22) |
            (static_cast<int64_t>(pes[11] & 0xfe) << 14) |
            (static_cast<int64_t>(pes[12]) << 7) |
            ((pes[13] & 0xfe) >> 1);
        const size_t pesHeaderDataLength = pes[8];
        const size_t id3PayloadOffset = 9 + pesHeaderDataLength;
        require(pesHeaderDataLength == 5, "ID3 PES must have the PTS-only header length");
        require(id3PayloadOffset + 20 <= available, "ID3 PES payload is truncated");
        const uint8_t* id3 = pes + id3PayloadOffset;
        require(id3[0] == 'I' && id3[1] == 'D' && id3[2] == '3',
                "ID3 must begin exactly at 9 + PES_header_data_length");
        const uint8_t* frame = id3 + 10;
        require(frame[0] == 'P' && frame[1] == 'R' && frame[2] == 'I' && frame[3] == 'V',
                "metadata frame must be PRIV");
        const int frameLength = synchsafe(frame + 4);
        const uint8_t* owner = frame + 10;
        const uint8_t* ownerEnd = std::find(owner, owner + frameLength, 0);
        require(ownerEnd < owner + frameLength, "PRIV owner terminator is missing");
        samples.push_back({
            pts,
            (pes[6] & 0x04) != 0,
            id3PayloadOffset,
            std::string(reinterpret_cast<const char*>(owner), reinterpret_cast<const char*>(ownerEnd)),
            std::vector<uint8_t>(ownerEnd + 1, owner + frameLength),
        });
    }
    return samples;
}

std::vector<uint8_t> runPipeline()
{
    CServiceFilter serviceFilter;
    serviceFilter.SetProgramNumberOrIndex(kServiceId);
    serviceFilter.SetAudio1Mode(13);
    serviceFilter.SetAudio2Mode(5);
    serviceFilter.SetCaptionMode(5);
    serviceFilter.SetSuperimposeMode(1);
    CID3Converter id3Converter;
    // -d 9: ID3 conversion + monotonic PTS, without the legacy five-byte
    // payload prefix that makes Media3 reject the sample.
    id3Converter.SetOption(9);

    std::vector<std::vector<uint8_t>> input;
    input.push_back(makePsiPacket(0, makePat()));
    input.push_back(makePsiPacket(kPmtPid, makePmt()));
    const std::vector<uint8_t> videoPayload = {0x00, 0x00, 0x01, 0xb3, 0x12, 0x34, 0x56};
    input.push_back(makeTsPacket(kVideoPid, true, videoPayload));
    input.push_back(makeTsPacket(kAudio1Pid, true, makePes(0xc0, 90'000, {0x11, 0x12})));
    input.push_back(makeTsPacket(kAudio1Pid, true, makePes(0xc0, 99'000, {0x13, 0x14}), 1));
    input.push_back(makeTsPacket(kAudio2Pid, true, makePes(0xc0, 90'000, {0x21, 0x22})));
    input.push_back(makeTsPacket(kAudio2Pid, true, makePes(0xc0, 99'000, {0x23, 0x24}), 1));
    input.push_back(makeTsPacket(kCaptionPid, true,
        makePes(0xbd, 180'000, {0x80, 0xff, 0x00, 0x10, 0x20})));
    input.push_back(makeTsPacket(kSuperimposePid, true,
        makePes(0xbd, 270'000, {0x81, 0xff, 0x00, 0x30, 0x40})));

    for (const std::vector<uint8_t>& packet : input) {
        serviceFilter.AddPacket(packet.data());
        const std::vector<uint8_t>& filtered = serviceFilter.GetPackets();
        for (size_t offset = 0; offset + 188 <= filtered.size(); offset += 188) {
            id3Converter.AddPacket(filtered.data() + offset);
        }
        serviceFilter.ClearPackets();
    }
    return id3Converter.GetPackets();
}

void verifyPipeline(const std::vector<uint8_t>& output)
{
    const std::vector<Stream> streams = parsePmtStreams(output, 0x01f0);
    require(hasStream(streams, 0x02, 0x0100), "MPEG-2 video stream type must remain H.262");
    require(hasStream(streams, 0x04, 0x0110), "primary audio track must remain visible");
    require(hasStream(streams, 0x04, 0x0111), "secondary audio track must remain visible");
    const int id3Pid = metadataPid(streams);
    require(id3Pid != 0, "ID3 metadata track must be present in PMT");

    bool videoPayloadFound = false;
    for (size_t offset = 0; offset + 188 <= output.size(); offset += 188) {
        const uint8_t* packet = output.data() + offset;
        if (extract_ts_header_pid(packet) != 0x0100) continue;
        const uint8_t* payload = payloadStart(packet);
        const size_t payloadSize = get_ts_payload_size(packet);
        const uint8_t expected[] = {0x00, 0x00, 0x01, 0xb3, 0x12, 0x34, 0x56};
        videoPayloadFound = payloadSize >= sizeof(expected) &&
            std::equal(expected, expected + sizeof(expected), payload);
    }
    require(videoPayloadFound, "MPEG-2 video payload must pass through unchanged");

    const std::vector<PrivSample> samples = parsePrivSamples(output, id3Pid);
    require(samples.size() == 2, "caption and superimpose must produce distinct ID3 samples");
    require(samples[0].dataAligned, "caption ID3 PES must set data_alignment_indicator");
    require(samples[1].dataAligned, "superimpose ID3 PES must set data_alignment_indicator");
    require(samples[0].id3PayloadOffset == 14 && samples[1].id3PayloadOffset == 14,
            "ID3 payload offset must be the PES header boundary without a zero prefix");
    require(samples[0].pts == 180'000 && samples[1].pts == 270'000,
            "ID3 samples must preserve PES PTS");
    require(samples[0].owner == "aribb24.js" && samples[1].owner == "aribb24.js",
            "ID3 PRIV owner must be aribb24.js");
    require(samples[0].payload == std::vector<uint8_t>({0x80, 0xff, 0x00, 0x10, 0x20}),
            "caption ID3 must preserve its original payload");
    require(samples[1].payload == std::vector<uint8_t>({0x81, 0xff, 0x00, 0x30, 0x40}),
            "superimpose ID3 must preserve its original payload");
}

void verifyOtherPrivateStreamIsNotConsumedById3Converter()
{
    CID3Converter converter;
    converter.SetOption(9);
    const std::vector<std::vector<uint8_t>> packets = {
        makePsiPacket(0, makePat()),
        makePsiPacket(kPmtPid, makePmt()),
        makeTsPacket(kOtherPrivatePid, true,
            makePes(0xbd, 360'000, {0x82, 0xff, 0x00, 0x55})),
    };
    for (const auto& packet : packets) converter.AddPacket(packet.data());
    const std::vector<uint8_t>& output = converter.GetPackets();
    const std::vector<Stream> streams = parsePmtStreams(output, kPmtPid);
    require(hasStream(streams, 0x06, kOtherPrivatePid),
            "non-caption private stream must remain in PMT");
    bool packetFound = false;
    for (size_t offset = 0; offset + 188 <= output.size(); offset += 188) {
        packetFound = packetFound || extract_ts_header_pid(output.data() + offset) == kOtherPrivatePid;
    }
    require(packetFound, "non-caption private stream packets must not be consumed");
}

} // namespace

int main(int argc, char* argv[])
{
    const std::vector<uint8_t> firstOpen = runPipeline();
    verifyPipeline(firstOpen);
    const std::vector<uint8_t> reopenedAfterSeek = runPipeline();
    verifyPipeline(reopenedAfterSeek);
    verifyOtherPrivateStreamIsNotConsumedById3Converter();
    if (argc == 2 && std::string(argv[1]) == "--emit-hex") {
        static const char kHex[] = "0123456789abcdef";
        for (uint8_t byte : firstOpen) {
            std::cout << kHex[byte >> 4] << kHex[byte & 0x0f];
        }
        std::cout << std::endl;
        return 0;
    }
    std::cout << "PASS: MPEG-2 tsreadex passthrough pipeline" << std::endl;
    return 0;
}
