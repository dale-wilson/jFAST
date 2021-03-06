package com.ociweb.pronghorn.ring;

import java.util.Arrays;

import com.ociweb.pronghorn.ring.token.OperatorMask;
import com.ociweb.pronghorn.ring.token.TokenBuilder;
import com.ociweb.pronghorn.ring.token.TypeMask;

public class FieldReferenceOffsetManager {
	
	public static final int SEQ     = 0x10000000;
	public static final int MSG_END = 0x80000000;
	
    public int preambleOffset; //-1 if there is no preamble
    public int templateOffset;
    
    public int tokensLen;
    public int[] fragDataSize;
    public int[] fragScriptSize;
    public int[] tokens;
    public int[] messageStarts;
    
    public final String[] fieldNameScript;
    public final long[] fieldIdScript;
    public int maximumFragmentStackDepth;  
    public final float maxVarFieldPerUnit;
    
    //TODO: B, set these upon construction if needed.
    //      for a given template/schema there is only 1 absent value that can be supported
	private final int absentInt = TokenBuilder.absentValue32(TokenBuilder.MASK_ABSENT_DEFAULT);
	private final long absentLong = TokenBuilder.absentValue64(TokenBuilder.MASK_ABSENT_DEFAULT); 
	
    
    private static int[] SINGLE_MESSAGE_BYTEARRAY_TOKENS = new int[]{TokenBuilder.buildToken(TypeMask.ByteArray, 
														                                      OperatorMask.Field_None, 
														                                      0)};
	private static String[] SINGLE_MESSAGE_BYTEARRAY_NAMES = new String[]{"ByteArray"};
	private static long[] SINGLE_MESSAGE_BYTEARRAY_IDS = new long[]{0};
	private static final short ZERO_PREMABLE = 0;
	public static final FieldReferenceOffsetManager RAW_BYTES = new FieldReferenceOffsetManager(SINGLE_MESSAGE_BYTEARRAY_TOKENS, 
			                                                                                    ZERO_PREMABLE, 
			                                                                                    SINGLE_MESSAGE_BYTEARRAY_NAMES, 
			                                                                                    SINGLE_MESSAGE_BYTEARRAY_IDS,
			                                                                                    "Chunked Stream");
		
	private final static int[] EMPTY = new int[0];
	private final String name;
	
    /**
     * Constructor is only for unit tests.
     */
    private FieldReferenceOffsetManager() {    	
    	this(SINGLE_MESSAGE_BYTEARRAY_TOKENS, ZERO_PREMABLE, SINGLE_MESSAGE_BYTEARRAY_NAMES, SINGLE_MESSAGE_BYTEARRAY_IDS);
    }

    public FieldReferenceOffsetManager(int[] scriptTokens, String[] scriptNames, long[] scriptIds) {
    	this(scriptTokens,(short)0,scriptNames,scriptIds);
    }    
    
    public FieldReferenceOffsetManager(int[] scriptTokens, short preableBytes, String[] scriptNames, long[] scriptIds) {
    	this(scriptTokens,preableBytes,scriptNames,scriptIds,null);
    }
    //NOTE: message fragments start at startsLocal values however they end when they hit end of group, sequence length or end the the array.
	public FieldReferenceOffsetManager(int[] scriptTokens, short preableBytes, String[] scriptNames, long[] scriptIds, String name) {
				
		this.name = name;
		//TODO: B, clientConfig must be able to skip reading the preamble,
        int PREAMBLE_MASK = 0xFFFFFFFF;//Set to zero when we are not sending the preamble
        
		int pb = PREAMBLE_MASK & preableBytes;
        if (pb<=0) {
            preambleOffset = -1;
            templateOffset = 0;
        } else {
            preambleOffset = 0;
            templateOffset = (pb+3)>>2;
        }
                
        
		if (null == scriptTokens) {
			tokens = EMPTY;
			messageStarts = computeMessageStarts(); 
			
			//Not convinced we should support this degenerate case (null script) but it does make some unit tests much easer to write.
            fragDataSize = null;
            fragScriptSize = null;
            maximumFragmentStackDepth = 0;
            maxVarFieldPerUnit = .5f;
        } else {
        	tokens = scriptTokens;
        	messageStarts = computeMessageStarts(); 
        	 
            fragDataSize  = new int[scriptTokens.length]; //size of fragments and offsets to fields, first field of each fragment need not use this!
            
            fragScriptSize = new int[scriptTokens.length];
            
            maximumFragmentStackDepth = scriptTokens.length;
            			
            maxVarFieldPerUnit = buildFragScript(scriptTokens, preableBytes, messageStarts.length>1 ? 1 : 0);
        }
        tokensLen = null==tokens?0:tokens.length;
        
        
        fieldNameScript = scriptNames;
        fieldIdScript = scriptIds;
	}

	public String toString() {
		if (null==name) {
			return fieldNameScript.length<20 ? Arrays.toString(fieldNameScript) : "ScriptLen:"+fieldNameScript.length; //TODO: find a better way to "make up" a name
		} else {
			return name;
		}
	}
	
    private float buildFragScript(int[] scriptTokens, short preableBytes, int spaceForTemplateId) {
		int scriptLength = scriptTokens.length;        
        boolean debug = false;       
        int i = 0;      
        int fragmentStartIdx=0;
        int depth = 0; //need script jump number
        
        boolean nextTokenOpensFragment = true;// false; //must capture simple case when we do not have wrapping group?
        
        //must count these to ensure that var field writes stay small enough to never cause a problem in the byte ring.
        int varLenFieldCount = 0;
        float varLenMaxDensity = 0; //max varLength fields per int on outer ring buffer than can ever happen
        
        while (i<scriptLength) {          
    //    	System.err.println("loading script at "+i);
        	
            //now past the end of the template so 
            //close it because this index starts a new one
            //first position is always part of a new template
            
            //sequences and optional groups will always have group tags.
            int tempToken = scriptTokens[i];
        	int type = TokenBuilder.extractType(tempToken);
            boolean isGroup = TypeMask.Group == type;    
            boolean isGroupOpen = isGroup && (0 == (tempToken & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
            boolean isGroupClosed = isGroup && (0 != (tempToken & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
            boolean isSeqLength = TypeMask.GroupLength == type;
                      
            if (isGroupOpen || nextTokenOpensFragment) {
                if (debug) {
                    System.err.println();
                }
       //         System.err.println("new fragmetn at "+i);
                if (varLenFieldCount>0) {
                	//Caution: do not modify this logic unless you take into account the fact that
                	//         * messages are made up of fragments and that some fragments are repeated others skipped
                	//         * messages are not always complete and only some (head or tail) fragments may be in the buffer
                	float varFieldPerUnit = varLenFieldCount/ (float)fragDataSize[fragmentStartIdx];
                	assert(varFieldPerUnit<=.5) : "It takes 2 units to write a var field so this will never be larger than .5";
                	
                	if (varFieldPerUnit>varLenMaxDensity) {
                		varLenMaxDensity = varFieldPerUnit;                		
                	}                	
                }
                
                //NOTE: need performance test after rounding up the fragment size to the next nearest cache line. 
                //fragDataSize[fragmentStartIdx] 
                
                depth++;                
                fragmentStartIdx = i;    
                
                boolean isSeq = (0 != (scriptTokens[i] & (OperatorMask.Group_Bit_Seq << TokenBuilder.SHIFT_OPER)));
                //TODO: B, if optional group it will also need to be zero like seq
                
                //must be a group open only for a new message 
                if (!isSeq && isGroupOpen) { 
					int preambleInts = (preableBytes+3)>>2;                               
                                                       
                    fragDataSize[fragmentStartIdx] = preambleInts+spaceForTemplateId;  //these are the starts of messages
                } else {
                	
                	fragDataSize[fragmentStartIdx] = 0; //these are the starts of fragments that are not message starts
                }
                varLenFieldCount = 0;//reset to zero so we can count the number of var fields for this next fragment
                
                
                nextTokenOpensFragment = false;
            }
            
            if (isGroupClosed) {
                depth--;
                nextTokenOpensFragment = true;
            }
            if (isSeqLength) {
                nextTokenOpensFragment = true;
            }
            
            int token = scriptTokens[i];
            
            fragDataSize[i]=fragDataSize[fragmentStartIdx]; //keep the individual offsets per field
            
            int tokenType = TokenBuilder.extractType(token);
			int fSize = TypeMask.ringBufferFieldSize[tokenType];

			varLenFieldCount += TypeMask.ringBufferFieldVarLen[tokenType];
            
            fragDataSize[fragmentStartIdx] += fSize;
            fragScriptSize[fragmentStartIdx]++;
            

            if (debug) {
                System.err.println(depth+"  "+i+"  "+TokenBuilder.tokenToString(scriptTokens[i]));
            }
            
            i++;
        }
        //must also add the very last fragment 
        if (varLenFieldCount>0) {
        	//Caution: do not modify this logic unless you take into account the fact that
        	//         * messages are made up of fragments and that some fragments are repeated others skipped
        	//         * messages are not always complete and only some (head or tail) fragments may be in the buffer
        	float varFieldPerUnit = varLenFieldCount/ (float)fragDataSize[fragmentStartIdx];
        	assert(varFieldPerUnit<=.5) : "It takes 2 units to write a var field so this will never be larger than .5";
        	
        	if (varFieldPerUnit>varLenMaxDensity) {
        		varLenMaxDensity = varFieldPerUnit;                		
        	}                	
        }
        
                
        if (debug) {
            System.err.println(Arrays.toString(fragDataSize));
            System.err.println(Arrays.toString(fragScriptSize));
            
        }
        return varLenMaxDensity;
	}
    
    
    public int[] messageStarts() {
    	return messageStarts;
    }
    
    public static boolean hasSingleMessageTemplate(FieldReferenceOffsetManager from) {
    	return 1 == from.messageStarts.length;
    }
    
    private int[] computeMessageStarts() {
		int countOfNeededStarts = 1; //zero is always a start regardless of the token type found at that location
		int j = tokens.length;
		while (--j>0) { //do not process zero we have already counted it
			int token = tokens[j];			
			
			if (TypeMask.Group == TokenBuilder.extractType(token) ) {				
				int opMask = TokenBuilder.extractOper(token);
				if ((OperatorMask.Group_Bit_Close & opMask)==0 &&     //this is an OPENING group not a CLOSE
				    (OperatorMask.Group_Bit_Templ & opMask)!=0 ) {    //this is a special GROUP called a TEMPLATE
					
					countOfNeededStarts ++;					
					
				}
			}
		}
		
		int[] result = new int[countOfNeededStarts];
				
		j = tokens.length;
		while (--j>0) { //do not process zero we have already counted it
			int token = tokens[j];			
			
			if (TypeMask.Group == TokenBuilder.extractType(token) ) {				
				int opMask = TokenBuilder.extractOper(token);
				if ((OperatorMask.Group_Bit_Close & opMask)==0 &&     //this is an OPENING group not a CLOSE
				    (OperatorMask.Group_Bit_Templ & opMask)!=0 ) {    //this is a special GROUP called a TEMPLATE
					
					result[--countOfNeededStarts] = j;							
					
				}
			}
		}
		result[--countOfNeededStarts] = 0;
		
		return result;
		
    }
    
    public static int lookupTemplateLocator(String name, FieldReferenceOffsetManager from) {
    	int i = from.messageStarts.length;
    	while(--i>=0) {
    		if (name.equals(from.fieldNameScript[from.messageStarts[i]])) {
    			return from.messageStarts[i];
    		}
    	}
    	throw new UnsupportedOperationException("Unable to find template name: "+name);
    }
    
    
    public static String lookupFieldName(int fragmentStart, int position, FieldReferenceOffsetManager from) {
		return from.fieldNameScript[fragmentStart+position];
	}
    
    /**
     * This does not return the token found in the script but rather a special value that can be used to 
     * get dead reckoning offset into the field location. 
     * 
     * @param target
     * @param framentStart
     * @param from
     * @return
     */
    public static int lookupFieldLocator(String target, int framentStart, FieldReferenceOffsetManager from) {
		int x = framentStart;
        
        int UPPER_BITS = 0xF0000000;
        
        while (true) {
        	//System.err.println("looking at:"+fieldNameScript[x]);
            if (from.fieldNameScript[x].equalsIgnoreCase(target)) {
                
                if (0==x) {
                    return UPPER_BITS|0; //that slot does not hold offset but rather full fragment size but we know zero can be used here.
                } else {
                    return UPPER_BITS|from.fragDataSize[x];                    
                }
                
            }
            
            int type = TokenBuilder.extractType(from.tokens[x]);
            boolean isGroup = TypeMask.Group == type;    
          //  boolean isGroupOpen = isGroup && (0 == (tokens[x] & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
            boolean isGroupClosed = isGroup && (0 != (from.tokens[x] & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
            boolean isSeqLength = TypeMask.GroupLength == type;
            
            if (isGroupClosed || isSeqLength) {
            	break;
            }
            
            x++;
        }
        throw new UnsupportedOperationException("Unable to find field name: "+target+" in "+Arrays.toString(from.fieldNameScript));
	}

    
    /**
     * Helpful debugging method that writes the script in a human readable form out to the console.
     * 
     * @param title
     * @param fullScript
     */
	public static void printScript(String title, FieldReferenceOffsetManager from) {
		System.out.println(title);
		int step = 3;
		
		String tab = "                                                 ";
		int i = 0;
		int depth = 3;
		while (i<from.tokens.length) {
			int token = from.tokens[i];
			
			if (TokenBuilder.extractType(token) ==  TypeMask.Group) {
				if ((TokenBuilder.extractOper(token)&OperatorMask.Group_Bit_Close)!=0 ) {
					depth-=step;
				}				
			}
			
			String row = "00000"+Integer.toString(i);
			
			String name = null!=from.fieldNameScript && i<from.fieldNameScript.length && null!=from.fieldNameScript[i] 
					        ? "   "+from.fieldNameScript[i] : "";
			
			System.out.println(row.substring(row.length()-6)+tab.substring(0,depth)+TokenBuilder.tokenToString(token)+name);		
			
			if (TokenBuilder.extractType(token) ==  TypeMask.Group) {
				if ((TokenBuilder.extractOper(token)&OperatorMask.Group_Bit_Close)==0 ) {
					depth+=step;
				} 				
			}
			i++;
		}		
	}
    
	//TODO: C, if this really really needs more specific values we may be able to give custom values per column #.
	public static int getAbsent32Value(FieldReferenceOffsetManager from) {
		return from.absentInt;
	}
	
	public static long getAbsent64Value(FieldReferenceOffsetManager from) {
		return from.absentLong;
	}
    
}
