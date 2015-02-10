/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.text.eia608;

import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.ParsableBitArray;
import com.google.android.exoplayer.util.ParsableByteArray;

import java.util.List;

/**
 * Facilitates the extraction and parsing of EIA-608 (a.k.a. "line 21 captions" and "CEA-608")
 * Closed Captions from the SEI data block from H.264.
 */
public class Eia608Parser {

  private static final int PAYLOAD_TYPE_CC = 4;
  private static final int COUNTRY_CODE = 0xB5;
  private static final int PROVIDER_CODE = 0x31;
  private static final int USER_ID = 0x47413934; // "GA94"
  private static final int USER_DATA_TYPE_CODE = 0x3;

  // Basic North American 608 CC char set, mostly ASCII. Indexed by (char-0x20).
  private static final int[] BASIC_CHARACTER_SET = new int[] {
    0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,     //   ! " # $ % & '
    0x28, 0x29,                                         // ( )
    0xE1,       // 2A: 225 'á' "Latin small letter A with acute"
    0x2B, 0x2C, 0x2D, 0x2E, 0x2F,                       //       + , - . /
    0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,     // 0 1 2 3 4 5 6 7
    0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F,     // 8 9 : ; < = > ?
    0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47,     // @ A B C D E F G
    0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,     // H I J K L M N O
    0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,     // P Q R S T U V W
    0x58, 0x59, 0x5A, 0x5B,                             // X Y Z [
    0xE9,       // 5C: 233 'é' "Latin small letter E with acute"
    0x5D,                                               //           ]
    0xED,       // 5E: 237 'í' "Latin small letter I with acute"
    0xF3,       // 5F: 243 'ó' "Latin small letter O with acute"
    0xFA,       // 60: 250 'ú' "Latin small letter U with acute"
    0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67,           //   a b c d e f g
    0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,     // h i j k l m n o
    0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77,     // p q r s t u v w
    0x78, 0x79, 0x7A,                                   // x y z
    0xE7,       // 7B: 231 'ç' "Latin small letter C with cedilla"
    0xF7,       // 7C: 247 '÷' "Division sign"
    0xD1,       // 7D: 209 'Ñ' "Latin capital letter N with tilde"
    0xF1,       // 7E: 241 'ñ' "Latin small letter N with tilde"
    0x25A0      // 7F:         "Black Square" (NB: 2588 = Full Block)
  };

  // Special North American 608 CC char set.
  private static final int[] SPECIAL_CHARACTER_SET = new int[] {
    0xAE,    // 30: 174 '®' "Registered Sign" - registered trademark symbol
    0xB0,    // 31: 176 '°' "Degree Sign"
    0xBD,    // 32: 189 '½' "Vulgar Fraction One Half" (1/2 symbol)
    0xBF,    // 33: 191 '¿' "Inverted Question Mark"
    0x2122,  // 34:         "Trade Mark Sign" (tm superscript)
    0xA2,    // 35: 162 '¢' "Cent Sign"
    0xA3,    // 36: 163 '£' "Pound Sign" - pounds sterling
    0x266A,  // 37:         "Eighth Note" - music note
    0xE0,    // 38: 224 'à' "Latin small letter A with grave"
    0x20,    // 39:         TRANSPARENT SPACE - for now use ordinary space
    0xE8,    // 3A: 232 'è' "Latin small letter E with grave"
    0xE2,    // 3B: 226 'â' "Latin small letter A with circumflex"
    0xEA,    // 3C: 234 'ê' "Latin small letter E with circumflex"
    0xEE,    // 3D: 238 'î' "Latin small letter I with circumflex"
    0xF4,    // 3E: 244 'ô' "Latin small letter O with circumflex"
    0xFB     // 3F: 251 'û' "Latin small letter U with circumflex"
  };

  private final ParsableBitArray seiBuffer;
  private final StringBuilder stringBuilder;

  /* package */ Eia608Parser() {
    seiBuffer = new ParsableBitArray();
    stringBuilder = new StringBuilder();
  }

  /* package */ boolean canParse(String mimeType) {
    return mimeType.equals(MimeTypes.APPLICATION_EIA608);
  }

  /* package */ void parse(byte[] data, int size, long timeUs, List<ClosedCaption> out) {
    if (size <= 0) {
      return;
    }

    stringBuilder.setLength(0);
    seiBuffer.reset(data);
    seiBuffer.skipBits(3); // reserved + process_cc_data_flag + zero_bit
    int ccCount = seiBuffer.readBits(5);
    seiBuffer.skipBits(8);

    for (int i = 0; i < ccCount; i++) {
      seiBuffer.skipBits(5); // one_bit + reserved
      boolean ccValid = seiBuffer.readBit();
      if (!ccValid) {
        seiBuffer.skipBits(18);
        continue;
      }
      int ccType = seiBuffer.readBits(2);
      if (ccType != 0) {
        seiBuffer.skipBits(16);
        continue;
      }
      seiBuffer.skipBits(1);
      byte ccData1 = (byte) seiBuffer.readBits(7);
      seiBuffer.skipBits(1);
      byte ccData2 = (byte) seiBuffer.readBits(7);

      // Ignore empty captions.
      if (ccData1 == 0 && ccData2 == 0) {
        continue;
      }

      // Special North American character set.
      if ((ccData1 == 0x11) && ((ccData2 & 0x70) == 0x30)) {
        stringBuilder.append(getSpecialChar(ccData2));
        continue;
      }

      // Control character.
      if (ccData1 < 0x20) {
        if (stringBuilder.length() > 0) {
          out.add(new ClosedCaptionText(stringBuilder.toString(), timeUs));
          stringBuilder.setLength(0);
        }
        out.add(new ClosedCaptionCtrl(ccData1, ccData2, timeUs));
        continue;
      }

      // Basic North American character set.
      stringBuilder.append(getChar(ccData1));
      if (ccData2 != 0) {
        stringBuilder.append(getChar(ccData2));
      }
    }

    if (stringBuilder.length() > 0) {
      out.add(new ClosedCaptionText(stringBuilder.toString(), timeUs));
    }
  }

  private static char getChar(byte ccData) {
    int index = (ccData & 0x7F) - 0x20;
    return (char) BASIC_CHARACTER_SET[index];
  }

  private static char getSpecialChar(byte ccData) {
    int index = ccData & 0xF;
    return (char) SPECIAL_CHARACTER_SET[index];
  }

  /**
   * Parses the beginning of SEI data and returns the size of underlying contains closed captions
   * data following the header. Returns 0 if the SEI doesn't contain any closed captions data.
   *
   * @param seiBuffer The buffer to read from.
   * @return The size of closed captions data.
   */
  public static int parseHeader(ParsableByteArray seiBuffer) {
    int b = 0;
    int payloadType = 0;

    do {
      b = seiBuffer.readUnsignedByte();
      payloadType += b;
    } while (b == 0xFF);

    if (payloadType != PAYLOAD_TYPE_CC) {
      return 0;
    }

    int payloadSize = 0;
    do {
      b = seiBuffer.readUnsignedByte();
      payloadSize += b;
    } while (b == 0xFF);

    if (payloadSize <= 0) {
      return 0;
    }

    int countryCode = seiBuffer.readUnsignedByte();
    if (countryCode != COUNTRY_CODE) {
      return 0;
    }
    int providerCode = seiBuffer.readUnsignedShort();
    if (providerCode != PROVIDER_CODE) {
      return 0;
    }
    int userIdentifier = seiBuffer.readInt();
    if (userIdentifier != USER_ID) {
      return 0;
    }
    int userDataTypeCode = seiBuffer.readUnsignedByte();
    if (userDataTypeCode != USER_DATA_TYPE_CODE) {
      return 0;
    }
    return payloadSize;
  }

}
