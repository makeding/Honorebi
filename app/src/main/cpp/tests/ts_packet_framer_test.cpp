#include "ts_packet_framer.hpp"
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include <array>
#include <cassert>
#include <iostream>
#include <iterator>

int main(int argc, char** argv) {
    if (argc > 1) {
        // Same framing/filter/ID3 chain as JNI, for bounded real-file diagnosis.
        TsPacketFramer framer;
        CServiceFilter filter;
        filter.SetProgramNumberOrIndex(333);
        filter.SetAudio1Mode(13); filter.SetAudio2Mode(5);
        filter.SetCaptionMode(5); filter.SetSuperimposeMode(1);
        CID3Converter id3; id3.SetOption(9);
        std::array<uint8_t, 32768> input;
        while (std::cin) {
            std::cin.read(reinterpret_cast<char*>(input.data()), input.size());
            framer.push(input.data(), std::cin.gcount(), [&](const uint8_t* p) { filter.AddPacket(p); });
            const auto& packets = filter.GetPackets();
            for (size_t i = 0; i < packets.size(); i += 188) id3.AddPacket(packets.data() + i);
            filter.ClearPackets();
            const auto& output = id3.GetPackets();
            std::cout.write(reinterpret_cast<const char*>(output.data()), output.size());
            id3.ClearPackets();
            assert(framer.pendingBytes() < 413);
        }
        return 0;
    }
    for (size_t stride : {188u, 192u, 204u}) {
        std::vector<uint8_t> original;
        for (int n = 0; n < 12; ++n) {
            std::vector<uint8_t> packet(stride, 0xaa);
            packet[0] = 0x47; packet[1] = 0x01; packet[2] = 0x00;
            packet[3] = 0x10 | (n & 15); packet[4] = n;
            original.insert(original.end(), packet.begin(), packet.end());
        }
        // Delete bytes from packet 4, followed by stray sync bytes in damaged data.
        auto damaged = original;
        damaged.erase(damaged.begin() + stride * 4, damaged.begin() + stride * 4 + 17);
        damaged.insert(damaged.begin() + stride * 4, {0x47, 0, 0, 0, 0});
        for (size_t chunk : {1u, 187u, 32768u}) {
            TsPacketFramer framer;
            std::vector<int> delivered;
            for (size_t offset = 0; offset < damaged.size(); offset += chunk) {
                framer.push(damaged.data() + offset, std::min(chunk, damaged.size() - offset),
                    [&](const uint8_t* p) { delivered.push_back(p[4]); });
                assert(framer.pendingBytes() < 413);
            }
            assert((delivered == std::vector<int>{0,1,2,3,5,6,7,8,9,10,11}));
        }
    }
    TsPacketFramer framer;
    std::vector<uint8_t> garbage(1000000, 0x47);
    for (int i = 0; i < 10; ++i) {
        framer.push(garbage.data(), garbage.size(), [](const uint8_t*) { assert(false); });
        assert(framer.pendingBytes() < 413);
    }
    std::cout << "TS packet framing: recovery, read boundaries, 188/192/204 and bounded garbage passed\n";
}
