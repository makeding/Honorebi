#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

// HTTP reads may split packets, and damaged recordings may lose bytes midstream.
// Keep only a bounded candidate prefix while searching; never parse misaligned data.
class TsPacketFramer {
public:
    template<class Consumer>
    void push(const uint8_t* input, size_t length, Consumer consume) {
        bytes_.insert(bytes_.end(), input, input + length);
        size_t position = 0;
        while (position < bytes_.size()) {
            if (unitSize_ != 0 && bytes_.size() - position < unitSize_) break;
            if (unitSize_ == 0 || !validHeader(position)) {
                unitSize_ = 0;
                bool found = false;
                for (; position + kProbeBytes <= bytes_.size(); ++position) {
                    for (size_t stride : {188u, 192u, 204u}) {
                        if (validHeader(position) && validHeader(position + stride) &&
                            validHeader(position + 2 * stride)) {
                            unitSize_ = stride;
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
                if (!found) break;
            }
            if (bytes_.size() - position < unitSize_) break;
            consume(bytes_.data() + position);
            position += unitSize_;
        }
        bytes_.erase(bytes_.begin(), bytes_.begin() + position);
    }

    size_t pendingBytes() const { return bytes_.size(); }

private:
    static constexpr size_t kProbeBytes = 2 * 204 + 5;
    bool validHeader(size_t position) const {
        if (position + 5 > bytes_.size() || bytes_[position] != 0x47) return false;
        const uint8_t control = bytes_[position + 3] & 0x30;
        if (control == 0) return false;
        return !(control & 0x20) || bytes_[position + 4] <= 183;
    }
    size_t unitSize_ = 0;
    std::vector<uint8_t> bytes_;
};
