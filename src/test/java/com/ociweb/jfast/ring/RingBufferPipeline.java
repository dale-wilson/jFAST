package com.ociweb.jfast.ring;

import static com.ociweb.jfast.ring.RingBuffer.addByteArray;
import static com.ociweb.jfast.ring.RingBuffer.byteBackingArray;
import static com.ociweb.jfast.ring.RingBuffer.byteMask;
import static com.ociweb.jfast.ring.RingBuffer.bytePosition;
import static com.ociweb.jfast.ring.RingBuffer.headPosition;
import static com.ociweb.jfast.ring.RingBuffer.publishWrites;
import static com.ociweb.jfast.ring.RingBuffer.releaseReadLock;
import static com.ociweb.jfast.ring.RingBuffer.spinBlockOnHead;
import static com.ociweb.jfast.ring.RingBuffer.spinBlockOnTail;
import static com.ociweb.jfast.ring.RingBuffer.tailPosition;
import static com.ociweb.jfast.ring.RingBuffer.takeRingByteLen;
import static com.ociweb.jfast.ring.RingBuffer.takeRingByteMetaData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class RingBufferPipeline {

	
	private final byte[] testArray = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ:,.-_+()*@@@@@@@@@@@@@@@".getBytes();//, this is a reasonable test message.".getBytes();
	private final int testMessages = 30000000;
	private final int stages = 5;
	private final byte primaryBits   = 16;
	private final byte secondaryBits = 28;//TODO: Warning if this is not big enough it will hang.
    
	@Test
	public void pipelineExample() {
		 		 
				 
		 //create all the threads, one for each stage
		 ExecutorService service = Executors.newFixedThreadPool(stages);
		 
		 //build all the rings
		 int j = stages-1;
		 RingBuffer[] rings = new RingBuffer[j];
		 while (--j>=0)  {
			 rings[j] = new RingBuffer(primaryBits,secondaryBits);
		 }
		 
		 //start the timer		 
		 long start = System.currentTimeMillis();
		 
		 //add all the stages start running
		 j = 0;
		 service.submit(createStage(rings[j]));
		 int i = stages-2;
		 while (--i>=0) {
			 service.submit(copyStage(rings[j++], rings[j]));			 
		 }
		 service.submit(dumpStage(rings[j]));
		 
		 //prevents any new jobs from getting submitted
		 service.shutdown();
		 //blocks until all the submitted runnables have stopped
		 try {
			service.awaitTermination(10, TimeUnit.MINUTES);
		 } catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		 }
		 long duration = System.currentTimeMillis()-start;
		 
		 long bytes = testMessages * testArray.length;
		 long bpms = (bytes*8)/duration;
		 System.out.println("Bytes:"+bytes+"  Gbits/sec:"+(bpms/1000000f)+" pipeline "+stages);
		 
		 		 
	}

	 	
	 
	private Runnable createStage(final RingBuffer outputRing) {
		
		
		return new Runnable() {

			@Override
			public void run() {
								
		      int fill =  (1<<(primaryBits>>2));
		      
	          int messageCount = testMessages;            
	          //keep local copy of the last time the tail was checked to avoid contention.
	          long tailPosCache = spinBlockOnTail(tailPosition(outputRing), headPosition(outputRing)-fill, outputRing);                        
	          while (--messageCount>=0) {
	        	  
	              //write the record
                  addByteArray(testArray, 0, testArray.length, outputRing);
                  
                  publishWrites(outputRing);
                  //wait for room to fit one message
                  //waiting on the tailPosition to move the others are constant for this scope.
                  //workingHeadPositoin is same or greater than headPosition
                  tailPosCache = spinBlockOnTail(tailPosCache, headPosition(outputRing)-fill, outputRing);
          
	          }

	          //send negative length as poison pill to exit all runnables  
	      	  addByteArray(testArray, 0, -1, outputRing);
	      	  publishWrites(outputRing); //must publish the posion or it just sits here and everyone down stream hangs
	      	  System.out.println("finished writing:"+testMessages);
			}
		};
	}

	//NOTE: this is an example of a stage that reads from one ring buffer and writes to another.
	private Runnable copyStage(final RingBuffer inputRing, final RingBuffer outputRing) {
		
		return new Runnable() {

			@Override
			public void run() {
                //only enter this block when we know there are records to read
    		    long inputTarget = 2;
                long headPosCache = spinBlockOnHead(headPosition(inputRing), inputTarget, inputRing);	
                
                int x = -2;
                
                //two per message, and we only want half the buffer to be full
                long outputTarget = 0-(1<<(primaryBits>>2));//this value is negative
                
                int mask = byteMask(outputRing); // data often loops around end of array so this mask is required
                long tailPosCache = spinBlockOnTail(tailPosition(outputRing), outputTarget, outputRing);
                while (true) {
                    //read the message
                    // 	System.out.println("reading:"+messageCount);
                    	
                	int meta = takeRingByteMetaData(inputRing);
                	int len = takeRingByteLen(inputRing);
                	
                	byte[] data = byteBackingArray(meta, inputRing);
                	int offset = bytePosition(meta, inputRing, len);

                	tailPosCache = spinBlockOnTail(tailPosCache, outputTarget, outputRing);
                	 //write the record

					
					if (len<0) {
						releaseReadLock(inputRing);  
						addByteArray(data, offset, len, outputRing);
						publishWrites(outputRing);
						return;
					}
					
					//TODO: there is a more elegant way to do this but ran out of time.
					if ((offset&mask) > ((offset+len) & mask)) {
						//rolled over the end of the buffer
						 int len1 = 1+mask-(offset&mask);
						 addByteArray(data, offset&mask, len1, outputRing);
						 addByteArray(data, 0, len-len1 ,outputRing);
						 outputTarget+=4;
					} else {						
						//simple add bytes
						 addByteArray(data, offset&mask, len, outputRing);
						 outputTarget+=2;
					}
                    publishWrites(outputRing);
                    
                    releaseReadLock(inputRing);  

                	
                	//block until one more byteVector is ready.
                	inputTarget += 2;
                	headPosCache = spinBlockOnHead(headPosCache, inputTarget, inputRing);	                        	    	                        		
                    
                }  
			}
		};
	}
	
	private Runnable dumpStage(final RingBuffer inputRing) {
		
		return new Runnable() {

			long total = 0;
			
            @Override
            public void run() {           	
    	            	
                    //only enter this block when we know there are records to read
        		    long target = 2;
                    long headPosCache = spinBlockOnHead(headPosition(inputRing), target, inputRing);	
                    long messageCount = 0;
                    while (true) {
                        //read the message
                        // 	System.out.println("reading:"+messageCount);
                        	
                    	int meta = takeRingByteMetaData(inputRing);
                    	int len = takeRingByteLen(inputRing);
                    	
    					byte[] data = byteBackingArray(meta, inputRing);
    					int offset = bytePosition(meta, inputRing, len);
    					int mask = byteMask(inputRing);
   					
    					
                    	//doing nothing with the data
                    	releaseReadLock(inputRing);

                    	if (len<0) {
                    		System.out.println("exited after reading: Msg:" + messageCount+" Bytes:"+total);
                    		return;
                    	}
                    	
                    	messageCount++;
                    	
                    	total += len;

                    	//block until one more byteVector is ready.
                    	target += 2;
                    	headPosCache = spinBlockOnHead(headPosCache, target, inputRing);	                        	    	                        		
                        
                    }   
                    
            }                
        };
	}

	
	 
	
}