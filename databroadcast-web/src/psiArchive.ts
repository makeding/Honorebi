interface PsiArchivedDataContext {
  pids?: number[];
  dict?: (Uint8Array | null)[];
  position?: number;
  trailerSize?: number;
  timeListCount?: number;
  codeListPosition?: number;
  codeCount?: number;
  initTime?: number;
  currTime?: number;
}

/**
 * Unpacks KonomiTV / EDCB `psi-archived-data` chunks into 188-byte MPEG-TS packets.
 */
export class PsiArchiveUnpacker {
  private buffer: Uint8Array = new Uint8Array(0);
  private ctx: PsiArchivedDataContext = {};
  private tsPacketCounters: Record<number, number> = {};

  reset(): void {
    this.buffer = new Uint8Array(0);
    this.ctx = {};
    this.tsPacketCounters = {};
  }

  push(
    chunk: Uint8Array,
    onPacket: (second: number, tsPackets: Uint8Array, pid: number) => void,
  ): void {
    const combined = new Uint8Array(this.buffer.length + chunk.length);
    combined.set(this.buffer);
    combined.set(chunk, this.buffer.length);

    const remaining = this.readChunk(combined.buffer, 0, (second, psiPackets, pid) => {
      this.setTsPacketHeader(psiPackets, pid);
      onPacket(second, psiPackets, pid);
    });
    if (remaining !== null) {
      this.buffer = new Uint8Array(remaining);
    }
  }

  private setTsPacketHeader(packets: Uint8Array, pid: number): void {
    let counter = this.tsPacketCounters[pid] || 0;
    for (let i = 0; i < packets.length; i += 188) {
      packets[i] = 0x47;
      packets[i + 1] = (i > 0 ? 0 : 0x40) | (pid >> 8);
      packets[i + 2] = pid & 0xff;
      packets[i + 3] = 0x10 | counter;
      counter = (counter + 1) & 0x0f;
    }
    this.tsPacketCounters[pid] = counter;
  }

  private readChunk(
    arrayBuffer: ArrayBuffer,
    startSecond: number,
    callback: (second: number, psiTsPackets: Uint8Array, pid: number) => void,
  ): ArrayBuffer | null {
    const data = new DataView(arrayBuffer);

    if (!this.ctx.pids) {
      this.ctx.pids = [];
      this.ctx.dict = [];
      this.ctx.position = 0;
      this.ctx.trailerSize = 0;
      this.ctx.timeListCount = -1;
      this.ctx.codeListPosition = 0;
      this.ctx.codeCount = 0;
      this.ctx.initTime = -1;
      this.ctx.currTime = -1;
    }

    while (data.byteLength - this.ctx.position! >= this.ctx.trailerSize! + 32) {
      let position = this.ctx.position! + this.ctx.trailerSize!;
      const timeListLen = data.getUint16(position + 10, true);
      const dictionaryLen = data.getUint16(position + 12, true);
      const dictionaryWindowLen = data.getUint16(position + 14, true);
      const dictionaryDataSize = data.getUint32(position + 16, true);
      const dictionaryBufferSize = data.getUint32(position + 20, true);
      const codeListLen = data.getUint32(position + 24, true);

      if (
        data.getUint32(position) !== 0x50737363 ||
        data.getUint32(position + 4) !== 0x0d0a9a0a ||
        dictionaryWindowLen < dictionaryLen ||
        dictionaryBufferSize < dictionaryDataSize ||
        dictionaryWindowLen > 65536 - 4096
      ) {
        return null;
      }

      const chunkSize =
        32 +
        timeListLen * 4 +
        dictionaryLen * 2 +
        Math.ceil(dictionaryDataSize / 2) * 2 +
        codeListLen * 2;

      if (data.byteLength - position < chunkSize) break;

      let timeListPosition = position + 32;
      position += 32 + timeListLen * 4;

      if (this.ctx.timeListCount! < 0) {
        const pids: number[] = [];
        const dict: (Uint8Array | null)[] = [];
        let sectionListPosition = 0;

        for (let i = 0; i < dictionaryLen; i++, position += 2) {
          const codeOrSize = data.getUint16(position, true) - 4096;
          if (codeOrSize >= 0) {
            if (codeOrSize >= this.ctx.pids.length || this.ctx.pids[codeOrSize] < 0) {
              return null;
            }
            pids[i] = this.ctx.pids[codeOrSize];
            dict[i] = this.ctx.dict![codeOrSize];
            this.ctx.pids[codeOrSize] = -1;
          } else {
            pids[i] = codeOrSize;
            dict[i] = null;
            sectionListPosition += 2;
          }
        }
        sectionListPosition += position;

        for (let i = 0; i < dictionaryLen; i++) {
          if (pids[i] >= 0) continue;
          const psi = new Uint8Array(data.buffer, sectionListPosition, pids[i] + 4097);
          const tsBuf = new Uint8Array(Math.ceil((psi.length + 1) / 184) * 188);
          for (let j = 0, k = 0; k < psi.length; j++, k++) {
            if (j % 188 === 0) {
              j += 4;
              if (k === 0) {
                tsBuf[j++] = 0;
              }
            }
            tsBuf[j] = psi[k];
          }
          dict[i] = tsBuf;
          sectionListPosition += psi.length;
          pids[i] = data.getUint16(position, true) & 0x1fff;
          position += 2;
        }

        for (let i = dictionaryLen, j = 0; i < dictionaryWindowLen; j++) {
          if (j >= this.ctx.pids.length) return null;
          if (this.ctx.pids[j] < 0) continue;
          pids[i] = this.ctx.pids[j];
          dict[i++] = this.ctx.dict![j];
        }
        this.ctx.pids = pids;
        this.ctx.dict = dict;
        this.ctx.timeListCount = 0;
        position = sectionListPosition + (dictionaryDataSize % 2);
      } else {
        position += dictionaryLen * 2 + Math.ceil(dictionaryDataSize / 2) * 2;
      }

      position += this.ctx.codeListPosition!;
      timeListPosition += this.ctx.timeListCount! * 4;
      for (
        ;
        this.ctx.timeListCount! < timeListLen;
        this.ctx.timeListCount!++, timeListPosition += 4
      ) {
        let initTime = this.ctx.initTime!;
        let currTime = this.ctx.currTime!;
        const absTime = data.getUint32(timeListPosition, true);
        if (absTime === 0xffffffff) {
          currTime = -1;
        } else if (absTime >= 0x80000000) {
          currTime = absTime & 0x3fffffff;
          if (initTime < 0) initTime = currTime;
        } else {
          const n = data.getUint16(timeListPosition + 2, true) + 1;
          if (currTime >= 0) {
            currTime += data.getUint16(timeListPosition, true);
            const sec = ((currTime + 0x40000000 - initTime) & 0x3fffffff) / 11250;
            if (sec >= startSecond) {
              for (
                ;
                this.ctx.codeCount! < n;
                this.ctx.codeCount!++, position += 2, this.ctx.codeListPosition! += 2
              ) {
                const code = data.getUint16(position, true) - 4096;
                const entry = this.ctx.dict![code];
                if (entry) {
                  callback(sec, entry, this.ctx.pids[code]);
                }
              }
              this.ctx.codeCount = 0;
            } else {
              position += n * 2;
              this.ctx.codeListPosition! += n * 2;
            }
          } else {
            position += n * 2;
            this.ctx.codeListPosition! += n * 2;
          }
        }
        this.ctx.initTime = initTime;
        this.ctx.currTime = currTime;
      }

      this.ctx.position = position;
      this.ctx.trailerSize = 2 + ((2 + chunkSize) % 4);
      this.ctx.timeListCount = -1;
      this.ctx.codeListPosition = 0;
      this.ctx.currTime = -1;
    }

    const ret = data.buffer.slice(this.ctx.position!);
    this.ctx.position = 0;
    return ret;
  }
}
