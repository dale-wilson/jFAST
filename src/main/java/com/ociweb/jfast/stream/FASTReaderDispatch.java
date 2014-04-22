//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import com.ociweb.jfast.field.ByteHeap;
import com.ociweb.jfast.field.FieldReaderBytes;
import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TextHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.field.StaticGlue;

//May drop interface if this causes a performance problem from virtual table
public class FASTReaderDispatch {

    final PrimitiveReader reader;

    private int readFromIdx = -1;

    // This is the GLOBAL dictionary
    // When unspecified in the template GLOBAL is the default so these are used.
    protected final int MAX_INT_INSTANCE_MASK;
    protected final int[] rIntDictionary;
    protected final int[] rIntInit;

    protected final int MAX_LONG_INSTANCE_MASK;
    protected final long[] rLongDictionary;
    protected final long[] rLongInit;

    protected final int DECIMAL_MAX_INT_INSTANCE_MASK;
    protected final int[] expDictionary;
    protected final int[] expInit;

    protected final int DECIMAL_MAX_LONG_INSTANCE_MASK;
    protected final long[] mantDictionary;
    protected final long[] mantInit;

    protected final int MAX_TEXT_INSTANCE_MASK;
    protected final FieldReaderBytes readerBytes;

    protected final int nonTemplatePMapSize;
    protected final int[][] dictionaryMembers;

    protected final DictionaryFactory dictionaryFactory;

    protected DispatchObserver observer;

    // constant fields are always the same or missing but never anything else.
    // manditory constant does not use pmap and has constant injected at
    // destnation never xmit
    // optional constant does use the pmap 1 (use initial const) 0 (not present)
    //
    // default fields can be the default or overridden this one time with a new
    // value.

    protected final int maxNestedSeqDepth;
    protected final int[] sequenceCountStack;

    int sequenceCountStackHead = -1;
    boolean doSequence; //NOTE: return value from closeGroup
    int jumpSequence; // Only needs to be set when returning true.

    protected final TextHeap charDictionary;
    protected final ByteHeap byteDictionary;

    int activeScriptCursor;
    int activeScriptLimit;

    int[] fullScript;

    protected final FASTRingBuffer queue;
    protected final int bfrMsk;
    protected final int[] bfr;

    public FASTReaderDispatch(PrimitiveReader reader, DictionaryFactory dcr, int nonTemplatePMapSize,
            int[][] dictionaryMembers, int maxTextLen, int maxVectorLen, int charGap, int bytesGap, int[] fullScript,
            int maxNestedGroupDepth, int primaryRingBits, int textRingBits) {
        this.reader = reader;
        this.dictionaryFactory = dcr;
        this.nonTemplatePMapSize = nonTemplatePMapSize;
        this.dictionaryMembers = dictionaryMembers;

        this.charDictionary = dcr.charDictionary(maxTextLen, charGap);
        this.byteDictionary = dcr.byteDictionary(maxVectorLen, bytesGap);

        this.maxNestedSeqDepth = maxNestedGroupDepth;
        this.sequenceCountStack = new int[maxNestedSeqDepth];

        this.fullScript = fullScript;

        this.rIntDictionary = dcr.integerDictionary();
        this.rIntInit = dcr.integerDictionary();
        assert (rIntDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rIntDictionary.length));
        assert (rIntDictionary.length == rIntInit.length);
        this.MAX_INT_INSTANCE_MASK = Math.min(TokenBuilder.MAX_INSTANCE, (rIntDictionary.length - 1));

        this.rLongDictionary = dcr.longDictionary();
        this.rLongInit = dcr.longDictionary();
        assert (rLongDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rLongDictionary.length));
        assert (rLongDictionary.length == rLongInit.length);

        this.MAX_LONG_INSTANCE_MASK = Math.min(TokenBuilder.MAX_INSTANCE, (rLongDictionary.length - 1));

        this.expDictionary = dcr.decimalExponentDictionary();
        this.expInit = dcr.decimalExponentDictionary();
        this.mantDictionary = dcr.decimalMantissaDictionary();
        this.mantInit = dcr.decimalMantissaDictionary();

        assert (expDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(expDictionary.length));
        assert (expDictionary.length == expInit.length);

        assert (mantDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(mantDictionary.length));
        assert (mantDictionary.length == mantInit.length);

        this.DECIMAL_MAX_INT_INSTANCE_MASK = Math.min(TokenBuilder.MAX_INSTANCE, (expDictionary.length - 1));
        this.DECIMAL_MAX_LONG_INSTANCE_MASK = Math.min(TokenBuilder.MAX_INSTANCE, (mantDictionary.length - 1));

        assert(null==charDictionary || charDictionary.itemCount()<TokenBuilder.MAX_INSTANCE);
        assert(null==charDictionary || TokenBuilder.isPowerOfTwo(charDictionary.itemCount()));
        this.MAX_TEXT_INSTANCE_MASK = (null==charDictionary)?TokenBuilder.MAX_INSTANCE:Math.min(TokenBuilder.MAX_INSTANCE, (charDictionary.itemCount()-1));

        this.readerBytes = new FieldReaderBytes(reader, byteDictionary);

        this.queue = new FASTRingBuffer((byte) primaryRingBits, (byte) textRingBits, charDictionary, byteDictionary);
        bfrMsk = queue.mask;
        bfr = queue.buffer;
        
    }

    public FASTRingBuffer ringBuffer() {
        return queue;
    }

    public void reset() {

        // clear all previous values to un-set
        dictionaryFactory.reset(rIntDictionary);
        dictionaryFactory.reset(rLongDictionary);
        dictionaryFactory.reset(expDictionary, mantDictionary);
        if (null != charDictionary) {
            charDictionary.reset();
        }
        if (null!=byteDictionary) {
            byteDictionary.reset();
        }
        sequenceCountStackHead = -1;

    }

    public void setScriptBlock(int cursor, int limit) {
        activeScriptCursor = cursor;
        activeScriptLimit = limit;
    }
    
    // long totalReadFields = 0;

    // The nested IFs for this short tree are slightly faster than switch
    // for more JVM configurations and when switch is faster (eg lots of JVM
    // -XX: args)
    // it is only slightly faster.

    // For a dramatic speed up of this dispatch code look into code generation
    // of the
    // script as a series of function calls against the specific
    // FieldReader*.class
    // This is expected to save 4ns per field on the AMD hardware or a speedup >
    // 12%.

    // Yet another idea is to process two tokens together and add a layer of
    // mangled functions that have "pre-coded" scripts. What if we just repeat
    // the same type?

    // totalReadFields++;

    
    
    // THOUGHTS
    // Build fixed length and put all in ring buffer, consumers can
    // look at leading int to determine what kind of message they have
    // and the script position can be looked up by field id once for their
    // needs.
    // each "mini-message is expected to be very small" and all in cache
    // package protected, unless we find a need to expose it?
    public boolean dispatchReadByToken() {

        // move everything needed in this tight loop to the stack
        int limit = activeScriptLimit;

        do {
            int token = fullScript[activeScriptCursor];

            assert (gatherReadData(reader, activeScriptCursor));

            // The trick here is to keep all the conditionals in this method and
            // do the work elsewhere.
            if (0 == (token & (16 << TokenBuilder.SHIFT_TYPE))) {
                // 0????
                if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                    // 00???
                    if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                        dispatchReadByTokenForInteger(token);
                    } else {
                        dispatchReadByTokenForLong(token);
                    }
                } else {
                    // 01???
                    if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                        dispatchReadByTokenForText(token);
                    } else {
                        // 011??
                        if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                            // 0110? Decimal and DecimalOptional
                            readDecimalExponent(token);
                            
                            //TODO A, Pass the optional bit flag on to Mantissa for var bit optionals
                            
                            token = fullScript[++activeScriptCursor]; //pull second token
                            readDecimalMantissa(token);
                        } else {
                            dispatchFieldBytes(token);
                        }
                    }
                }
            } else {
                // 1????
                if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                    // 10???
                    if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                        // 100??
                        // Group Type, no others defined so no need to keep
                        // checking
                        if (0 == (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER))) {
                            // this is NOT a message/template so the
                            // non-template pmapSize is used.
                            if (nonTemplatePMapSize > 0) {
                                genReadGroupPMapOpen();
                            }
                        } else {
                            int idx = TokenBuilder.MAX_INSTANCE & token;
                            closeGroup(token,idx);
                            return doSequence;//checkSequence && completeSequence(idx); // TODO: B,
                                                                  // this is in
                                                                  // a very poor
                                                                  // place for
                                                                  // optimization.
                        }

                    } else {
                        // 101??

                        // Length Type, no others defined so no need to keep
                        // checking
                        // Only happens once before a node sequence so push it
                        // on the count stack
                        readLength(token,activeScriptCursor + (TokenBuilder.MAX_INSTANCE & fullScript[1+activeScriptCursor]) + 1);

                    }
                } else {
                    // 11???
                    // Dictionary Type, no others defined so no need to keep
                    // checking
                    if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
                        readDictionaryReset2(dictionaryMembers[TokenBuilder.MAX_INSTANCE & token]);
                    } else {
                        // OperatorMask.Dictionary_Read_From 0001
                        // next read will need to use this index to pull the
                        // right initial value.
                        // after it is used it must be cleared/reset to -1
                        readDictionaryFromField(token);
                    }
                }
            }
        } while (++activeScriptCursor < limit);
        return false;
    }

    //TODO: generator must track previous read from for text etc
    //TODO: generator must track if previous is not used then do not write to dictionary.
    
    
    private void dispatchFieldBytes(int token) {
        
        // 0111?
        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
            // 01110 ByteArray
            readByteArray(token);
        } else {
            // 01111 ByteArrayOptional
            readByteArrayOptional(token);
        }
        
    }
    protected int sequenceJump(int length, int cursor) {
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            cursor += (TokenBuilder.MAX_INSTANCE & fullScript[++cursor]) + 1;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
        }
        return cursor;
    }

    private void readDictionaryFromField(int token) {
        readFromIdx = TokenBuilder.MAX_INSTANCE & token;
    }

    public void setDispatchObserver(DispatchObserver observer) {
        this.observer = observer;
    }

    protected boolean gatherReadData(PrimitiveReader reader, int cursor) {

        int token = fullScript[cursor];

        if (null != observer) {
            String value = "";
            // totalRead is bytes loaded from stream.

            long absPos = reader.totalRead() - reader.bytesReadyToParse();
            observer.tokenItem(absPos, token, cursor, value);
        }

        return true;
    }

    protected boolean gatherReadData(PrimitiveReader reader, int cursor, String value) {

        int token = fullScript[cursor];

        if (null != observer) {
            // totalRead is bytes loaded from stream.

            long absPos = reader.totalRead() - reader.bytesReadyToParse();
            observer.tokenItem(absPos, token, cursor, value);
        }

        return true;
    }

    protected boolean gatherReadData(PrimitiveReader reader, String msg) {

        if (null != observer) {
            long absPos = reader.totalRead() - reader.bytesReadyToParse();
            observer.tokenItem(absPos, -1, activeScriptCursor, msg);
        }

        return true;
    }

    private void readDictionaryReset2(int[] members) {

        int limit = members.length;
        int m = 0;
        int idx = members[m++]; // assumes that a dictionary always has at lest
                                // 1 member
        while (m < limit) {
            assert (idx < 0);

            if (0 == (idx & 8)) {
                if (0 == (idx & 4)) {
                    // integer
                    while (m < limit && (idx = members[m++]) >= 0) {
                        genReadDictionaryIntegerReset(idx);
                    }
                } else {
                    // long
                    // System.err.println("long");
                    while (m < limit && (idx = members[m++]) >= 0) {
                        genReadDictionaryLongReset(idx);
                    }
                }
            } else {
                if (0 == (idx & 4)) {
                    // text
                    while (m < limit && (idx = members[m++]) >= 0) {
                        genReadDictionaryTextReset(idx);
                    }
                } else {
                    if (0 == (idx & 2)) {
                        // decimal
                        // System.err.println("decimal");
                        while (m < limit && (idx = members[m++]) >= 0) {
                            genReadDictionaryDecimalReset(idx);
                        }
                    } else {
                        // bytes
                        while (m < limit && (idx = members[m++]) >= 0) {
                            genReadDictionaryBytesReset(idx);
                        }
                    }
                }
            }
        }
    }

    private void dispatchReadByTokenForText(int token) {
        // System.err.println(" CharToken:"+TokenBuilder.tokenToString(token));

        // 010??
        if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
            // 0100?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 01000 TextASCII
                readTextASCII(token);
            } else {
                // 01001 TextASCIIOptional
                readTextASCIIOptional(token);
            }
        } else {
            // 0101?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 01010 TextUTF8
                readTextUTF8(token);
            } else {
                // 01011 TextUTF8Optional
                readTextUTF8Optional(token);
            }
        }
    }

    private void dispatchReadByTokenForLong(int token) {
        // 001??
        if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
            // 0010?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 00100 LongUnsigned
                readLongUnsigned(token);
            } else {
                // 00101 LongUnsignedOptional
                readLongUnsignedOptional(token);
            }
        } else {
            // 0011?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 00110 LongSigned
                readLongSigned(token, rLongDictionary, MAX_LONG_INSTANCE_MASK);
            } else {
                // 00111 LongSignedOptional
                readLongSignedOptional(token, rLongDictionary, MAX_LONG_INSTANCE_MASK);
            }
        }
    }

    private void dispatchReadByTokenForInteger(int token) {
        // 000??
        if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
            // 0000?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 00000 IntegerUnsigned
                readIntegerUnsigned(token);
            } else {
                // 00001 IntegerUnsignedOptional
                readIntegerUnsignedOptional(token);
            }
        } else {
            // 0001?
            if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                // 00010 IntegerSigned
                readIntegerSigned(token, rIntDictionary, MAX_INT_INSTANCE_MASK);
            } else {
                // 00011 IntegerSignedOptional
                readIntegerSignedOptional(token, rIntDictionary, MAX_INT_INSTANCE_MASK);
            }
        }
    }

    public long readLong(int token) {

        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            // not optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                readLongUnsigned(token);
            } else {
                readLongSigned(token, rLongDictionary, MAX_LONG_INSTANCE_MASK);
            }
        } else {
            // optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                readLongUnsignedOptional(token);
            } else {
                readLongSignedOptional(token, rLongDictionary, MAX_LONG_INSTANCE_MASK);
            }
        }
        //NOTE: for testing we need to check what was written
        return FASTRingBuffer.peekLong(bfr, queue.addPos-2, bfrMsk);
    }

    private void readLongSignedOptional(int token, long[] rLongDictionary, int instanceMask) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongSignedNoneOptional(constAbsent);
                } else {
                    // delta
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongSignedDeltaOptional(target, source, constAbsent, rLongDictionary);
                }
            } else {
                // constant
                long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));
                long constConst = rLongDictionary[token & instanceMask];

                genReadLongSignedConstantOptional(constAbsent, constConst);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongSignedCopyOptional(target, source, constAbsent, rLongDictionary);
                } else {
                    // increment
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongSignedIncrementOptional(target, source, constAbsent, rLongDictionary);
                }
            } else {
                // default
                long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));
                long constDefault = rLongDictionary[token & instanceMask] == 0 ? constAbsent
                        : rLongDictionary[token & instanceMask];

                genReadLongSignedDefaultOptional(constAbsent, constDefault);
            }
        }

    }

    private void readLongSigned(int token, long[] rLongDictionary, int instanceMask) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int target = token & instanceMask;

                    genReadLongSignedNone(target, rLongDictionary);
                } else {
                    // delta
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;

                    genReadLongSignedDelta(target, source, rLongDictionary);
                }
            } else {
                // constant
                // always return this required value.
                long constDefault = rLongDictionary[token & instanceMask];
                genReadLongSignedConstant(constDefault);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;

                    genReadLongSignedCopy(target, source, rLongDictionary);
                } else {
                    // increment
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;

                    genReadLongSignedIncrement(target, source, rLongDictionary);
                }
            } else {
                // default
                long constDefault = rLongDictionary[token & instanceMask];

                genReadLongSignedDefault(constDefault);
            }
        }
    }

    private void readLongUnsignedOptional(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongUnsignedOptional(constAbsent);
                } else {
                    // delta
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongUnsignedDeltaOptional(target, source, constAbsent, rLongDictionary);
                }
            } else {
                // constant
                long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));
                long constConst = rLongDictionary[token & MAX_LONG_INSTANCE_MASK];

                genReadLongUnsignedConstantOptional(constAbsent, constConst);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongUnsignedCopyOptional(target, source, constAbsent, rLongDictionary);
                } else {
                    // increment
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;
                    long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));

                    genReadLongUnsignedIncrementOptional(target, source, constAbsent, rLongDictionary);
                }
            } else {
                // default
                long constAbsent = TokenBuilder.absentValue64(TokenBuilder.extractAbsent(token));
                long constDefault = rLongDictionary[token & MAX_LONG_INSTANCE_MASK] == 0 ? constAbsent
                        : rLongDictionary[token & MAX_LONG_INSTANCE_MASK];

                genReadLongUnsignedDefaultOptional(constAbsent, constDefault);
            }
        }

    }

    private void readLongUnsigned(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int target = token & MAX_LONG_INSTANCE_MASK;

                    genReadLongUnsignedNone(target, rLongDictionary);
                } else {
                    // delta
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;

                    genReadLongUnsignedDelta(target, source, rLongDictionary);
                }
            } else {
                // constant
                // always return this required value.
                long constDefault = rLongDictionary[token & MAX_LONG_INSTANCE_MASK];
                genReadLongConstant(constDefault);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;

                    genReadLongUnsignedCopy(target, source, rLongDictionary);
                } else {
                    // increment
                    int target = token & MAX_LONG_INSTANCE_MASK;
                    int source = readFromIdx > 0 ? readFromIdx & MAX_LONG_INSTANCE_MASK : target;

                    genReadLongUnsignedIncrement(target, source, rLongDictionary);
                }
            } else {
                // default
                long constDefault = rLongDictionary[token & MAX_LONG_INSTANCE_MASK];

                genReadLongUnsignedDefault(constDefault);
            }
        }

    }

    public int readInt(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            // not optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                readIntegerUnsigned(token);
            } else {
                readIntegerSigned(token, rIntDictionary, MAX_INT_INSTANCE_MASK);
            }
        } else {
            // optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                readIntegerUnsignedOptional(token);
            } else {
                readIntegerSignedOptional(token, rIntDictionary, MAX_INT_INSTANCE_MASK);
            }
        }
        //NOTE: for testing we need to check what was written
        return FASTRingBuffer.peek(bfr, queue.addPos-1, bfrMsk);
    }

    private void readIntegerSignedOptional(int token, int[] rIntDictionary, int instanceMask) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerSignedOptional(constAbsent);
                } else {
                    // delta
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerSignedDeltaOptional(target, source, constAbsent, rIntDictionary);
                }
            } else {
                // constant
                int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));
                int constConst = rIntDictionary[token & instanceMask];

                genReadIntegerSignedConstantOptional(constAbsent, constConst);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerSignedCopyOptional(target, source, constAbsent, rIntDictionary);
                } else {
                    // increment
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerSignedIncrementOptional(target, source, constAbsent, rIntDictionary);
                }
            } else {
                // default
                int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));
                int constDefault = rIntDictionary[token & instanceMask] == 0 ? constAbsent
                        : rIntDictionary[token & instanceMask];

                genReadIntegerSignedDefaultOptional(constAbsent, constDefault);
            }
        }

    }

    private void readIntegerSigned(int token, int[] rIntDictionary, int instanceMask) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int target = token & instanceMask;
                    genReadIntegerSignedNone(target);
                } else {
                    // delta
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    genReadIntegerSignedDelta(target, source, rIntDictionary);
                }
            } else {
                // constant
                // always return this required value.
                int constDefault = rIntDictionary[token & instanceMask];
                genReadIntegerConstant(constDefault);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    genReadIntegerSignedCopy(target, source, rIntDictionary);
                } else {
                    // increment
                    int target = token & instanceMask;
                    int source = readFromIdx > 0 ? readFromIdx & instanceMask : target;
                    genReadIntegerSignedIncrement(target, source, rIntDictionary);
                }
            } else {
                // default
                int constDefault = rIntDictionary[token & instanceMask];
                genReadIntegerSignedDefault(constDefault);
            }
        }
    }

    private void readIntegerUnsignedOptional(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    assert (readFromIdx < 0);
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerUnsignedOptional(constAbsent);
                } else {
                    // delta
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerUnsignedDeltaOptional(target, source, constAbsent, rIntDictionary);
                }
            } else {
                // constant
                int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));
                int constConst = rIntDictionary[token & MAX_INT_INSTANCE_MASK];

                genReadIntegerUnsignedConstantOptional(constAbsent, constConst);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerUnsignedCopyOptional(target, source, constAbsent, rIntDictionary);
                } else {
                    // increment
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                    int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));

                    genReadIntegerUnsignedIncrementOptional(target, source, constAbsent, rIntDictionary);
                }
            } else {
                // default
                int target = token & MAX_INT_INSTANCE_MASK;
                int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                int constAbsent = TokenBuilder.absentValue32(TokenBuilder.extractAbsent(token));
                int t = rIntDictionary[source];
                int constDefault = t == 0 ? constAbsent : t - 1;

                genReadIntegerUnsignedDefaultOptional(constAbsent, constDefault);
            }
        }

    }

    private void readIntegerUnsigned(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                int target = token & MAX_INT_INSTANCE_MASK;
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    genReadIntegerUnsigned(target);
                } else {
                    // delta
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                    genReadIntegerUnsignedDelta(target, source, rIntDictionary);
                }
            } else {
                // constant
                // always return this required value.
                int constDefault = rIntDictionary[token & MAX_INT_INSTANCE_MASK];
                genReadIntegerUnsignedConstant(constDefault);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;

                    genReadIntegerUnsignedCopy(target, source, rIntDictionary);
                } else {
                    // increment
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;

                    genReadIntegerUnsignedIncrement(target, source, rIntDictionary);
                }
            } else {
                // default
                int target = token & MAX_INT_INSTANCE_MASK;
                int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                int constDefault = rIntDictionary[source];

                genReadIntegerUnsignedDefault(constDefault);
            }
        }
    }

    private void readLength(int token, int jumpToTarget) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                int target = token & MAX_INT_INSTANCE_MASK;
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    genReadLength(target, jumpToTarget);
                } else {
                    // delta
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                    genReadLengthDelta(target, source, jumpToTarget, rIntDictionary);
                }
            } else {
                // constant
                // always return this required value.
                int constDefault = rIntDictionary[token & MAX_INT_INSTANCE_MASK];
                genReadLengthConstant(constDefault, jumpToTarget);
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;

                    genReadLengthCopy(target, source, jumpToTarget, rIntDictionary);
                } else {
                    // increment
                    int target = token & MAX_INT_INSTANCE_MASK;
                    int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;

                    genReadLengthIncrement(target, source, jumpToTarget, rIntDictionary);
                }
            } else {
                // default
                int target = token & MAX_INT_INSTANCE_MASK;
                int source = readFromIdx >= 0 ? readFromIdx & MAX_INT_INSTANCE_MASK : target;
                int constDefault = rIntDictionary[source];

                genReadLengthDefault(constDefault, jumpToTarget);
            }
        }
    }
    
    public int readBytes(int token) {

        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        // System.out.println("reading "+TokenBuilder.tokenToString(token));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            readByteArray(token);
        } else {
            readByteArrayOptional(token);
        }
        
        //NOTE: for testing we need to check what was written
        int value = FASTRingBuffer.peek(bfr, queue.addPos-2, bfrMsk);
        //if the value is positive it no longer points to the textHeap so we need
        //to make a replacement here for testing.
        return value<0? value : token & MAX_TEXT_INSTANCE_MASK;
    }

    private void readByteArray(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesNone(idx);
                } else {
                    // tail
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesTail(idx);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    int id =  token & readerBytes.INSTANCE_MASK;
                    int idLength = byteDictionary.length(id);
                    genReadBytesConstant(id, idLength);
                } else {
                    // delta
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesDelta(idx);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                int idx = token & readerBytes.INSTANCE_MASK;
                genReadBytesCopy(idx, 0);
            } else {
                // default
                int id =  token & readerBytes.INSTANCE_MASK;
                int idLength = byteDictionary.length(id);
                genReadBytesDefault(id,idLength, 0);
            }
        }
    }



    private void readByteArrayOptional(int token) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesNoneOptional(idx);
                } else {
                    // tail
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesTailOptional(idx);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    int constInit =    (token & readerBytes.INSTANCE_MASK) | StaticGlue.INIT_VALUE_MASK;
                    int constInitLen = byteDictionary.length(constInit);
                    
                    int constValue =    token & readerBytes.INSTANCE_MASK;
                    int constValueLen = byteDictionary.length(constValue);
                    
                    genReadBytesConstantOptional(constInit, constInitLen, constValue, constValueLen);
                } else {
                    // delta
                    int idx = token & readerBytes.INSTANCE_MASK;
                    genReadBytesDeltaOptional(idx);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                int idx = token & readerBytes.INSTANCE_MASK;
                genReadBytesCopy(idx, 1);
            } else {
                // default
                int constValue =    token & readerBytes.INSTANCE_MASK;
                int constValueLen = byteDictionary.length(constValue);
                genReadBytesDefault(constValue,constValueLen,1);
            }
        }
    }



    public void openGroup(int token, int pmapSize) {

        assert (token < 0);
        assert (0 == (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));

        if (pmapSize > 0) {
            reader.openPMap(pmapSize);
        }
    }

    public void closeGroup(int token, int backvalue) {

        assert (token < 0);
        assert (0 != (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));

        if (0 != (token & (OperatorMask.Group_Bit_PMap << TokenBuilder.SHIFT_OPER))) {
            genReadGroupClose();
        }

        //token driven logic so nothing will need to be generated for this false case
        if (0!=(token & (OperatorMask.Group_Bit_Seq << TokenBuilder.SHIFT_OPER))) {
            genReadSequenceClose(backvalue);
        } else {
            doSequence = false;
        }
        
    }


    
    public int readDecimalExponent(int token) {
        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        
        // TODO: A, Optional absent null is not implemented yet for Decimal type. should swap in "special" operation for mantissa.
        // This can be done in a similar way to how the token is adjusted in order to use the normal int processing.
        
        //Use Int exponent but we need to shift the bits first to move the operator
        
        int expoToken = token&
                      ((TokenBuilder.MASK_TYPE<<TokenBuilder.SHIFT_TYPE)| 
                       (TokenBuilder.MASK_ABSENT<<TokenBuilder.SHIFT_ABSENT)|
                       (TokenBuilder.MAX_INSTANCE));
        expoToken |= (token>>TokenBuilder.SHIFT_OPER_DECIMAL_EX)&(TokenBuilder.MASK_OPER<<TokenBuilder.SHIFT_OPER);
        expoToken |= 0x80000000;
        
        if (0 == (expoToken & (1 << TokenBuilder.SHIFT_TYPE))) {
            // 00010 IntegerSigned
            readIntegerSigned(expoToken, expDictionary, DECIMAL_MAX_INT_INSTANCE_MASK);
        } else {
            // 00011 IntegerSignedOptional
            readIntegerSignedOptional(expoToken, expDictionary, DECIMAL_MAX_INT_INSTANCE_MASK);
        }
        //NOTE: for testing we need to check what was written
        return FASTRingBuffer.peek(bfr, queue.addPos-1, bfrMsk);
    }

    public long readDecimalMantissa(int token) {
        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
            // not optional
            readLongSigned(token, mantDictionary, DECIMAL_MAX_LONG_INSTANCE_MASK);
        } else {
            // optional
            readLongSignedOptional(token, mantDictionary, DECIMAL_MAX_LONG_INSTANCE_MASK);
        }
        
        //NOTE: for testing we need to check what was written
        return FASTRingBuffer.peekLong(bfr, queue.addPos-2, bfrMsk);
    };

    public int readText(int token) {
        assert (0 == (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii
                readTextASCII(token);
            } else {
                // utf8
                readTextUTF8(token);
            }
        } else {
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii optional
                readTextASCIIOptional(token);
            } else {
                // utf8 optional
                readTextUTF8Optional(token);
            }
        }
        
        //NOTE: for testing we need to check what was written
        int value = FASTRingBuffer.peek(bfr, queue.addPos-2, bfrMsk);
        //if the value is positive it no longer points to the textHeap so we need
        //to make a replacement here for testing.
        return value<0? value : token & MAX_TEXT_INSTANCE_MASK;
    }

    private void readTextUTF8Optional(int token) {
        int idx = token & MAX_TEXT_INSTANCE_MASK;

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    genReadUTF8None(idx,1);
                } else {
                    // tail
                    genReadUTF8TailOptional(idx);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    int constInit = (token & MAX_TEXT_INSTANCE_MASK) | StaticGlue.INIT_VALUE_MASK;
                    int constValue = token & MAX_TEXT_INSTANCE_MASK;
                    genReadTextConstantOptional(constInit, constValue, charDictionary.initLength(constInit), charDictionary.initLength(constValue));
                } else {
                    // delta
                    genReadUTF8DeltaOptional(idx);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                genReadUTF8Copy(idx,1);
            } else {
                // default
                genReadUTF8Default(idx, charDictionary.initLength(StaticGlue.INIT_VALUE_MASK|idx), 1);
            }
        }
    }

    private void readTextASCII(int token) {
        int idx = token & MAX_TEXT_INSTANCE_MASK;

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    genReadASCIINone(idx);//always dynamic
                } else {
                    // tail
                    int fromIdx = readFromIdx;
                    genReadASCIITail(idx, fromIdx);//always dynamic
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    int constIdx = idx | StaticGlue.INIT_VALUE_MASK;
                    genReadTextConstant(constIdx, charDictionary.initLength(constIdx)); //always fixed length
                } else {
                    // delta
                    genReadASCIIDelta(idx);//always dynamic
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                genReadASCIICopy(idx); //always dynamic
            } else {
                // default
                genReadASCIIDefault(idx, charDictionary.initLength(StaticGlue.INIT_VALUE_MASK|idx)); //dynamic or constant
            }
        }
    }

    private void readTextUTF8(int token) {
        int idx = token & MAX_TEXT_INSTANCE_MASK;

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    genReadUTF8None(idx,0);
                } else {
                    // tail
                    genReadUTF8Tail(idx);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    int constIdx = idx | StaticGlue.INIT_VALUE_MASK;
                    genReadTextConstant(constIdx, charDictionary.initLength(constIdx));
                } else {
                    // delta
                    genReadUTF8Delta(idx);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                genReadUTF8Copy(idx,0);
            } else {
                // default
                genReadUTF8Default(idx, charDictionary.initLength(StaticGlue.INIT_VALUE_MASK|idx), 0);
            }
        }
    }

    private void readTextASCIIOptional(int token) {
        int idx = token & MAX_TEXT_INSTANCE_MASK;
        if (0 == (token & ((4 | 2 | 1) << TokenBuilder.SHIFT_OPER))) {
            if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                // none
                genReadASCIINone(idx);
            } else {
                // tail
                genReadASCIITailOptional(idx);
            }
        } else {
            if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
                if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                    genReadASCIIDeltaOptional(readFromIdx, idx);
                } else {
                    int constInit = (token & MAX_TEXT_INSTANCE_MASK) | StaticGlue.INIT_VALUE_MASK;
                    int constValue = token & MAX_TEXT_INSTANCE_MASK;
                    genReadTextConstantOptional(constInit, constValue, charDictionary.initLength(constInit), charDictionary.initLength(constValue));
                }
            } else {
                if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                    genReadASCIICopyOptional(idx);
                } else {
                    // for ASCII we don't need special behavior for optional
                    genReadASCIIDefault(idx, charDictionary.initLength(StaticGlue.INIT_VALUE_MASK|idx));
                }
            }
        }
    }

    //////////////////////////////////
    //Code above this point is copied for generator
    //////////////////////////////////
    
    protected int spclPosInc() { //generated code has its own instance of this.
        return queue.addPos++;
    }

    
    // ////////////////////////////////////////////////////////////
    // DO NOT REMOVE/MODIFY CONSTANT
    public static final String START_HERE = "Code Generator Scripts Start Here";

    
    // ////////////////////////////////////////////////////////////


    protected void genReadSequenceClose(int backvalue) {
        if (sequenceCountStackHead <= 0) {
            // no sequence to worry about or not the right time
            doSequence = false;
        } else {
        
            // each sequence will need to repeat the pmap but we only need to push
            // and pop the stack when the sequence is first encountered.
            // if count is zero we can pop it off but not until then.
        
            if (--sequenceCountStack[sequenceCountStackHead] < 1) {
                // this group is a sequence so pop it off the stack.
                --sequenceCountStackHead;
                // finished this sequence so leave pointer where it is
                jumpSequence = 0;
            } else {
                // do this sequence again so move pointer back
                jumpSequence = backvalue;
            }
            doSequence = true;
        }
    }
    
    protected void genReadGroupPMapOpen() {
        reader.openPMap(nonTemplatePMapSize);
    }

    protected void genReadGroupClose() {
        reader.closePMap();
    }
    
    
    //length methods
    protected boolean genReadLengthDefault(int constDefault,  int jumpToTarget) {
        
        int length;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, length = reader.readIntegerUnsignedDefault(constDefault));
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
            return false;
       }
    }

    //TODO: C, once this all works find a better way to inline it with only 1 conditional.
    protected boolean genReadLengthIncrement(int target, int source,  int jumpToTarget, int[] rIntDictionary) {
        int length;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, length = reader.readIntegerUnsignedIncrement(target, source, rIntDictionary));
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
            return false;
       }
    }

    protected boolean genReadLengthCopy(int target, int source,  int jumpToTarget, int[] rIntDictionary) {
        int length;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, length = reader.readIntegerUnsignedCopy(target, source, rIntDictionary));
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
            return false;
       }
    }

    protected boolean genReadLengthConstant(int constDefault,  int jumpToTarget) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constDefault);
        if (constDefault == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = constDefault;
            return false;
       }
    }

    protected boolean genReadLengthDelta(int target, int source,  int jumpToTarget, int[] rIntDictionary) {
        int length;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, length = reader.readIntegerUnsignedDelta(target, source, rIntDictionary));
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
            return false;
       }
    }

    protected boolean genReadLength(int target,  int jumpToTarget) {
        int length;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, rIntDictionary[target] = length = reader.readIntegerUnsigned());
        if (length == 0) {
            // jumping over sequence (forward) it was skipped (rare case)
            activeScriptCursor = jumpToTarget;
            return true;
        } else {
            // jumpSequence = 0;
            sequenceCountStack[++sequenceCountStackHead] = length;
            return false;
       }
    }

    // int methods

    protected void genReadIntegerUnsignedDefaultOptional(int constAbsent, int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedDefaultOptional(constDefault, constAbsent));
    }

    protected void genReadIntegerUnsignedIncrementOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedIncrementOptional(target, source, rIntDictionary, constAbsent));
    }

    protected void genReadIntegerUnsignedCopyOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.staticReadIntegerUnsignedCopyOptional(target, source, constAbsent, reader, rIntDictionary));
    }

    protected void genReadIntegerUnsignedConstantOptional(int constAbsent, int constConst) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedConstantOptional(constAbsent, constConst));
    }

    protected void genReadIntegerUnsignedDeltaOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedDeltaOptional(target, source, rIntDictionary, constAbsent));
    }

    protected void genReadIntegerUnsignedOptional(int constAbsent) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.staticReadIntegerUnsignedOptional(constAbsent, reader));
    }

    protected void genReadIntegerUnsignedDefault(int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedDefault(constDefault));
    }

    protected void genReadIntegerUnsignedIncrement(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedIncrement(target, source, rIntDictionary));
    }

    protected void genReadIntegerUnsignedCopy(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedCopy(target, source, rIntDictionary));
    }

    protected void genReadIntegerUnsignedConstant(int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constDefault);
    }

    protected void genReadIntegerUnsignedDelta(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerUnsignedDelta(target, source, rIntDictionary));
    }

    protected void genReadIntegerUnsigned(int target) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, rIntDictionary[target] = reader.readIntegerUnsigned());
    }

    protected void genReadIntegerSignedDefault(int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedDefault(constDefault));
    }

    protected void genReadIntegerSignedIncrement(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedIncrement(target, source, rIntDictionary));
    }

    protected void genReadIntegerSignedCopy(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedCopy(target, source, rIntDictionary));
    }

    protected void genReadIntegerConstant(int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constDefault);
    }

    protected void genReadIntegerSignedDelta(int target, int source, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedDelta(target, source, rIntDictionary));
    }

    protected void genReadIntegerSignedNone(int target) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, rIntDictionary[target] = reader.readIntegerSigned());
    }

    protected void genReadIntegerSignedDefaultOptional(int constAbsent, int constDefault) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedDefaultOptional(constDefault, constAbsent));
    }

    protected void genReadIntegerSignedIncrementOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedIncrementOptional(target, source, rIntDictionary, constAbsent));
    }

    protected void genReadIntegerSignedCopyOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.staticReadIntegerSignedCopyOptional(target, source, constAbsent, reader, rIntDictionary));
    }

    protected void genReadIntegerSignedConstantOptional(int constAbsent, int constConst) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedConstantOptional(constAbsent, constConst));
    }

    protected void genReadIntegerSignedDeltaOptional(int target, int source, int constAbsent, int[] rIntDictionary) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedDeltaOptional(target, source, rIntDictionary, constAbsent));
    }

    protected void genReadIntegerSignedOptional(int constAbsent) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, reader.readIntegerSignedOptional(constAbsent));
    }

    // long methods

    protected void genReadLongUnsignedDefault(long constDefault) {
        {
        long tmpLng=reader.readLongUnsignedDefault(constDefault);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedIncrement(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsignedIncrement(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedCopy(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsignedCopy(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongConstant(long constDefault) {
        {
        long tmpLng=constDefault;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedDelta(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsignedDelta(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedNone(int idx, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsigned(idx, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedDefaultOptional(long constAbsent, long constDefault) {
        {
        long tmpLng=reader.readLongUnsignedDefaultOptional(constDefault, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedIncrementOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsignedIncrementOptional(idx, source, rLongDictionary, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedCopyOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=StaticGlue.staticReadLongUnsignedCopyOptional(idx, source, constAbsent, reader, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedConstantOptional(long constAbsent, long constConst) {
        {
        long tmpLng=reader.readLongUnsignedConstantOptional(constAbsent, constConst);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedDeltaOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongUnsignedDeltaOptional(idx, source, rLongDictionary, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongUnsignedOptional(long constAbsent) {
        {
        long tmpLng=reader.readLongUnsignedOptional(constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedDefault(long constDefault) {
        {
        long tmpLng=reader.readLongSignedDefault(constDefault);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedIncrement(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSignedIncrement(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedCopy(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSignedCopy(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedConstant(long constDefault) {
        {
        long tmpLng=constDefault;
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedDelta(int idx, int source, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSignedDelta(idx, source, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedNone(int idx, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSigned(idx, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedDefaultOptional(long constAbsent, long constDefault) {
        {
        long tmpLng=reader.readLongSignedDefaultOptional(constDefault, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedIncrementOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSignedIncrementOptional(idx, source, rLongDictionary, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedCopyOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=StaticGlue.staticReadLongSignedCopyOptional(idx, source, constAbsent, reader, rLongDictionary);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedConstantOptional(long constAbsent, long constConst) {
        {    //TODO: A, if long const and default values are sent as int tuples then the shift mask logic can be skipped!!!
        long tmpLng=reader.readLongSignedConstantOptional(constAbsent, constConst);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedDeltaOptional(int idx, int source, long constAbsent, long[] rLongDictionary) {
        {
        long tmpLng=reader.readLongSignedDeltaOptional(idx, source, rLongDictionary, constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    protected void genReadLongSignedNoneOptional(long constAbsent) {
        {
        long tmpLng=reader.readLongSignedOptional(constAbsent);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng >>> 32)); 
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, (int) (tmpLng & 0xFFFFFFFF));
        }
    }

    // text methods.

    
    protected void genReadUTF8None(int idx, int optOff) {
        {
        StaticGlue.readUTF8s(idx,optOff, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadUTF8TailOptional(int idx) {
        {
        StaticGlue.readUTF8TailOptional(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadUTF8DeltaOptional(int idx) {
        {
        StaticGlue.readUTF8DeltaOptional(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }
    
    protected void genReadUTF8Delta(int idx) {
        {
        StaticGlue.readUTF8Delta(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }
    
    protected void genReadASCIITail(int idx, int fromIdx) {
        {
        StaticGlue.readASCIITail(idx, reader.readIntegerUnsigned(), fromIdx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadTextConstant(int constIdx, int constLen) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constIdx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constLen);
    }

    protected void genReadASCIIDelta(int idx) {
        {
        int trim = reader.readIntegerSigned();
        if (trim >=0) {
            StaticGlue.readASCIITail(idx, trim, readFromIdx, charDictionary, reader); 
        } else {
            StaticGlue.readASCIIHead(idx, trim, readFromIdx, charDictionary, reader);
        }
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadASCIICopy(int idx) {
        {
        if (reader.popPMapBit()!=0) {
            StaticGlue.readASCIIToHeap(idx, charDictionary, reader);
        }
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadUTF8Tail(int idx) {
        {
        StaticGlue.readUTF8Tail(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }


    protected void genReadUTF8Copy(int idx, int optOff) {
        {
        if (reader.popPMapBit() != 0) {
                StaticGlue.readUTF8s(idx,optOff,charDictionary,reader);
        }
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadUTF8Default(int idx, int defLen, int optOff) {
        if (0 == reader.popPMapBit()) {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.INIT_VALUE_MASK | idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, defLen);
        } else {
            StaticGlue.readUTF8s(idx, optOff, charDictionary, reader);
            int len = charDictionary.valueLength(idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadASCIINone(int idx) {
        {
        StaticGlue.readASCIIToHeap(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadASCIITailOptional(int idx) {
        {
        StaticGlue.readASCIITailOptional(idx, charDictionary, reader);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }
    
    protected void genReadASCIIDeltaOptional(int fromIdx, int idx) {
        {//TODO: B, extract the constant length from here.
        int optionalTrim = reader.readIntegerSigned();
        int tempId = (0 == optionalTrim ? 
                         StaticGlue.INIT_VALUE_MASK | idx : 
                         StaticGlue.readASCIIDeltaOptional2(fromIdx, idx,
        optionalTrim, charDictionary, reader));
        int len = charDictionary.valueLength(tempId);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(tempId, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadTextConstantOptional(int constInit, int constValue, int constInitLen, int constValueLen) {
        if (0 != reader.popPMapBit() ) {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constInit);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constInitLen);
        } else {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constValue);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constValueLen);
        }
    }

    protected void genReadASCIICopyOptional(int idx) {
        {
        StaticGlue.staticReadASCIICopyOptional(idx, reader, charDictionary);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadASCIIDefault(int idx, int defLen) {
        if (0 == reader.popPMapBit()) {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.INIT_VALUE_MASK | idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, defLen);
        } else {
            StaticGlue.readASCIIToHeap(idx, charDictionary, reader);
            int len = charDictionary.valueLength(idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }
    
    //byte methods
    
    protected void genReadBytesConstant(int constIdx, int constLen) {
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constIdx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constLen);
    }

    protected void genReadBytesConstantOptional(int constInit, int constInitLen, int constValue, int constValueLen) {
        
        if (0 != reader.popPMapBit() ) {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constInit);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constInitLen);
        } else {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constValue);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, constValueLen);
        }
    }

    protected void genReadBytesDefault(int idx, int defLen, int optOff) {
        
        if (0 == reader.popPMapBit()) {
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, StaticGlue.INIT_VALUE_MASK | idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, defLen);
        } else {
            readerBytes.readBytesData(idx, optOff);
            int len = byteDictionary.valueLength(idx);
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
            FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadBytesCopy(int idx, int optOff) {
        {
        if (reader.popPMapBit() != 0) {
            readerBytes.readBytesData(idx,optOff);
        }
        int len = byteDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadBytesDeltaOptional(int idx) {
        {
        readerBytes.readBytesDeltaOptional2(idx);
        int len = byteDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadBytesTailOptional(int idx) {
        {
        readerBytes.readBytesTailOptional2(idx);
        int len = byteDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadBytesDelta(int idx) {
        {
        readerBytes.readBytesDelta2(idx);
        int len = byteDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }
    
    
    protected void genReadBytesTail(int idx) {
        {
        readerBytes.readBytesTail2(idx);
        int len = byteDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeBytesToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    
    protected void genReadBytesNoneOptional(int idx) {
        {
        readerBytes.readBytesData(idx, 1);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    protected void genReadBytesNone(int idx) {
        {
        readerBytes.readBytesData(idx,0);
        int len = charDictionary.valueLength(idx);
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, queue.writeTextToRingBuffer(idx, len));
        FASTRingBuffer.appendi(bfr, spclPosInc(), bfrMsk, len);
        }
    }

    // dictionary reset

    protected void genReadDictionaryBytesReset(int idx) {
        readerBytes.reset(idx);
    }

    protected void genReadDictionaryDecimalReset(int idx) {
        expDictionary[idx] = expInit[idx];
        mantDictionary[idx] = mantInit[idx];
    }

    protected void genReadDictionaryTextReset(int idx) {
        charDictionary.reset(idx);
    }

    protected void genReadDictionaryLongReset(int idx) {
        rLongDictionary[idx] = rLongInit[idx];
    }

    protected void genReadDictionaryIntegerReset(int idx) {
        rIntDictionary[idx] = rIntInit[idx];
    }

}
