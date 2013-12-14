package com.ociweb.jfast.stream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.primitive.PrimitiveReaderWriterTest;



public class StreamingLongTest extends BaseStreamingTest {

	final int fields         = 1000;
	final long[] testData     = buildTestDataUnsigned(fields);
	final int fieldsPerGroup = 10;
	final int maxMPapBytes   = (int)Math.ceil(fieldsPerGroup/7d);
	final int groupToken = buildGroupToken(maxMPapBytes,0);//TODO: repeat still unsupported

	boolean sendNulls = true;
	
	//NO PMAP
	//NONE, DELTA, and CONSTANT(non-optional)
	
	//Constant can never be optional but can have pmap.
		
	@Test
	public void longUnsignedTest() {
		int[] types = new int[] {
                  TypeMask.LongUnsigned,
		    	  TypeMask.LongUnsignedOptional,
				  };
		
		int[] operators = new int[] {
                OperatorMask.None,  //no need for pmap
                OperatorMask.Delta, //no need for pmap
                OperatorMask.Copy,
                OperatorMask.Increment,
                OperatorMask.Constant, //test runner knows not to use with optional
                OperatorMask.Default
                };
				
		tester(types, operators, "UnsignedLong");
	}
	
	@Test
	public void longSignedTest() {
		int[] types = new int[] {
                  TypeMask.LongSigned,
				  TypeMask.LongSignedOptional,
				};
		
		int[] operators = new int[] {
                OperatorMask.None,  //no need for pmap
                OperatorMask.Delta, //no need for pmap
                OperatorMask.Copy,
                OperatorMask.Increment,
                OperatorMask.Constant, //test runner knows not to use with optional
                OperatorMask.Default
                };
		tester(types, operators, "SignedLong");
	}
	
	
	private void tester(int[] types, int[] operators, String label) {	
		
		int operationIters = 7;
		int warmup         = 50;
		int sampleSize     = 1000;
		String readLabel = "Read "+label+" groups of "+fieldsPerGroup+" ";
		String writeLabel = "Write "+label+" groups of "+fieldsPerGroup;
		
		int streamByteSize = operationIters*((maxMPapBytes*(fields/fieldsPerGroup))+(fields*4));
		int maxGroupCount = operationIters*fields/fieldsPerGroup;
		
		
		int[] tokenLookup = HomogeniousRecordWriteReadLongBenchmark.buildTokens(fields, types, operators);
		
		byte[] writeBuffer = new byte[streamByteSize];

		///////////////////////////////
		//test the writing performance.
		//////////////////////////////
		
		long byteCount = performanceWriteTest(fields, fieldsPerGroup, maxMPapBytes, operationIters, warmup, sampleSize,
				writeLabel, streamByteSize, maxGroupCount, tokenLookup, writeBuffer);

		///////////////////////////////
		//test the reading performance.
		//////////////////////////////
		
		performanceReadTest(fields, fieldsPerGroup, maxMPapBytes, operationIters, warmup, sampleSize, readLabel,
				streamByteSize, maxGroupCount, tokenLookup, byteCount, writeBuffer);
		
	}

	@Override
	protected long timeWriteLoop(int fields, int fieldsPerGroup, int maxMPapBytes, int operationIters,
			int[] tokenLookup, FASTStaticWriter fw) {
		
		long start = System.nanoTime();
		if (operationIters<3) {
			throw new UnsupportedOperationException("must allow operations to have 3 data points but only had "+operationIters);
		}
				
		writeData(fields, fieldsPerGroup, operationIters, tokenLookup, fw, groupToken);
				
		return System.nanoTime() - start;
	}
	

	protected void writeData(int fields, int fieldsPerGroup, int operationIters,
								int[] tokenLookup,
								FASTStaticWriter fw, int groupToken) {
		int i = operationIters;
		int g = fieldsPerGroup;
		fw.openGroup(groupToken);
		
		while (--i>=0) {
			int f = fields;
		
			while (--f>=0) {
				
				int token = tokenLookup[f]; 
				
				if (sendNulls && ((f&0xF)==0) && (0!=(token&0x1000000))) {
					fw.write(token);
				} else {
					fw.write(token, testData[f]); 
				}
							
				g = groupManagementWrite(fieldsPerGroup, fw, i, g, groupToken, f);				
			}			
		}
		if (fw.isGroupOpen()) {
			fw.closeGroup(groupToken);
		}
		fw.flush();
		fw.flush();
	}

	@Override
	protected long timeReadLoop(int fields, int fieldsPerGroup, int maxMPapBytes, 
			                      int operationIters, int[] tokenLookup,
								  FASTStaticReader fr) {
		long start = System.nanoTime();
		if (operationIters<3) {
			throw new UnsupportedOperationException("must allow operations to have 3 data points but only had "+operationIters);
		}
			
		readData(fields, fieldsPerGroup, operationIters, tokenLookup, fr);
			
		long duration = System.nanoTime() - start;
		return duration;
	}

	protected void readData(int fields, int fieldsPerGroup, int operationIters,
			                  int[] tokenLookup, FASTStaticReader fr) {
		
		long none = Integer.MIN_VALUE/2;
		
		int i = operationIters;
		int g = fieldsPerGroup;
		
		fr.openGroup(groupToken);
		
		while (--i>=0) {
			int f = fields;
			
			while (--f>=0) {
				
				int token = tokenLookup[f]; 	
				if (sendNulls && (f&0xF)==0 && (0!=(token&0x1000000))) {
		     		long value = fr.readLong(tokenLookup[f], none);
					if (none!=value) {
						assertEquals(none, value);
					}
				} else { 
					long value = fr.readLong(tokenLookup[f], none);
					if (testData[f]!=value) {
						assertEquals(testData[f], value);
					}
				}
				g = groupManagementRead(fieldsPerGroup, fr, i, g, groupToken, f);				
			}			
		}
		if (fr.isGroupOpen()) {
			fr.closeGroup(groupToken);
		}
	}


	long[] buildTestDataUnsigned(int count) {
		
		long[] seedData = PrimitiveReaderWriterTest.unsignedLongData;
		int s = seedData.length;
		int i = count;
		long[] target = new long[count];
		while (--i>=0) {
			target[i] = seedData[--s];
			if (0==s) {
				s=seedData.length;
			}
		}
		return target;
	}
	
}