package com.ociweb.jfast.field.integer;

import com.ociweb.jfast.FASTAccept;
import com.ociweb.jfast.FASTProvide;
import com.ociweb.jfast.NullAdjuster;
import com.ociweb.jfast.ReadWriteEntry;
import com.ociweb.jfast.ValueDictionaryEntry;
import com.ociweb.jfast.field.Field;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.read.FieldTypeReadWrite;
import com.ociweb.jfast.read.ReadEntry;
import com.ociweb.jfast.write.WriteEntry;

public final class FieldLongNulls extends Field {

	private final int id;
	private final int repeat;
	//old next field
	
	public FieldLongNulls(int id, ValueDictionaryEntry valueDictionaryEntry) {
		
		this.id = id;
		//next assignment	
		this.repeat = 1;
	}

	public final void reader(PrimitiveReader reader, FASTAccept visitor) {
		if (reader.peekNull()) {
			reader.incPosition();
			visitor.accept(id);
		} else {
			visitor.accept(id, reader.readSignedLongNullable());
		}
		//end of reader
	}

	public void writer(PrimitiveWriter writer, FASTProvide provider) {
		if (provider.provideNull(id)) {
			writer.writeNull();
		} else { 
			writer.writeSignedLongNullable(provider.provideLong(id));
		}
		//end of writer
	}
	
	public void reset() {
		//end of reset
	}

}