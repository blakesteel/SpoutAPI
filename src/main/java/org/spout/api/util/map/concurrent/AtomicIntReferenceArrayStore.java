package org.spout.api.util.map.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import org.spout.api.datatable.DatatableSequenceNumber;
import org.spout.api.math.MathHelper;

/**
 * Implements a store that stores {int, &lt;T&gt;} elementa.<br>
 * <br>
 * When an element is added, it is stored at an index decided by the store.<br>
 * <br>
 * The arrays used to store the elements are dynamically resized as more elements are added.<br>
 * <br>
 * The number of elements added to the store may temporarily exceed the max value, but only if a removal is performed shortly afterwards.  The store will spin lock until the number of elements is brought below the limit, so the period of the violation should be short.<br>
 * <br>
 * A method is provided to test if an index is a reserved index, based on the maximum lengths of the arrays.  Only reserved indexes are used as element indexes.<br>
 * @param <T> the type of the Object in the {int, &lt;T&gt;} pair
 */
public final class AtomicIntReferenceArrayStore<T> {

	@SuppressWarnings("unchecked")
	private final T EMPTY = (T)new Object();

	private final int maxLength;
	private AtomicInteger length = new AtomicInteger(0);
	private AtomicInteger entries = new AtomicInteger(0);
	private AtomicInteger scan = new AtomicInteger(0);
	private final int reservedMask;

	private AtomicReference<AtomicIntegerArray> seqArray;
	private AtomicReference<T[]> auxArray;
	private AtomicReference<int[]> intArray;

	public AtomicIntReferenceArrayStore(int maxEntries) {
		this(maxEntries, 0.49);
	}
	
	public AtomicIntReferenceArrayStore(int maxEntries, double loadFactor) {
		this(maxEntries, loadFactor, 0);
	}
	
	@SuppressWarnings("unchecked")
	public AtomicIntReferenceArrayStore(int maxEntries, double loadFactor, int initialSize) {
		this.maxLength = MathHelper.roundUpPow2((int)(maxEntries / loadFactor));
		this.reservedMask = (-MathHelper.roundUpPow2(maxLength)) & 0xFFFF;

		this.length.set(MathHelper.roundUpPow2(initialSize));
		this.entries.set(0);

		intArray = new AtomicReference<int[]>(new int[this.length.get()]);
		auxArray = new AtomicReference<T[]>((T[])new Object[this.length.get()]);
		seqArray = new AtomicReference<AtomicIntegerArray>(new AtomicIntegerArray(this.length.get()));
		emptyFill(auxArray.get(), seqArray.get());
	}

	/**
	 * Indicates if the given short should be reserved.<br>
	 * <br>
	 * Only ids where isReverved(id) returns true will be returned by the add(...) method.<br>
	 * <br>
	 * Ids from (65536 - length) to 65535 are reserved.
	 * <br>
	 * The top 2 bytes of the id are ignored.<br>
	 * @param id
	 * @return true if the id is reserved
	 */
	public final boolean isReserved(int id) {
		id = id & 0x0000FFFF;
		return (id & reservedMask) == reservedMask;
	}

	/**
	 * Gets the int value stored at a given index.<br>
	 * <br>
	 * If there is no int stored at the index, then the return value is undefined.<br>
	 * <br>
	 * @param index the index
	 * @return the int value
	 */
	public final int getInt(int index) {
		index = toInternal(index);
		while (true) {
			int initialSequence = seqArray.get().get(index);
			if (initialSequence == DatatableSequenceNumber.UNSTABLE) {
				continue;
			}
			int value = intArray.get()[index];
			if (seqArray.get().get(index) != initialSequence) {
				continue;
			}
			return value;
		}
	}
	
	/**
	 * Gets the sequence number associated with the element at a given index.
	 * 
	 * A sequence number of DatatableSequenceNumber.UNSTABLE indicates that the record is unstable.
	 * 
	 * @param index the index
	 * @return the sequence number
	 */
	public int getSequence(int index) {
		return this.seqArray.get().get(toInternal(index));
	}

	/**
	 * Gets the id value stored at a given index.<br>
	 * <br>
	 * If there is no int stored at the index, then the return value is undefined.<br>
	 * <br>
	 * @param index the index
	 * @return the int value
	 */
	public final short getId(int index) {
		return (short)(getInt(index) >> 16);
	}

	/**
	 * Gets the data value stored at a given index.<br>
	 * <br>
	 * If there is no int stored at the index, then the return value is undefined.<br>
	 * <br>
	 * @param index the index
	 * @return the int value
	 */
	public final short getData(int index) {
		return (short)(getInt(index));
	}

	/**
	 * Gets the auxiliary data at a given index.<br>
	 * <br>
	 * If there is no data (int or auxiliary data) stored at the index, then the return value is the EMPTY object.<br>
	 * <br>
	 * If there is no auxiliary data, but there is int data, then the method returns null.<br>
	 * <br>
	 * This EMPTY object is NOT a valid &lt;T&gt; object.<br>
	 * <br>
	 * @param index the index
	 * @return the auxiliary data object, null, or EMPTY
	 */
	public final T getAuxData(int index) {
		index = toInternal(index);
		while (true) {
			int initialSequence = seqArray.get().get(index);
			if (initialSequence == DatatableSequenceNumber.UNSTABLE) {
				continue;
			}
			T auxData = auxArray.get()[index];
			if (seqArray.get().get(index) != initialSequence) {
				continue;
			}
			return auxData;
		}
	}

	/**
	 * Adds an entry to the store.  The auxData parameter should be set to null to indicate no auxiliary data.<br>
	 * <br>
	 * The index that is returned is guaranteed to be one of the reserved indexes.<br>
	 * <br>
	 * @param id the id
	 * @param data the data
	 * @param auxData the auxiliary data
	 * @return the index that the entry was stored in the array
	 */
	public final int add(short id, short data, T auxData) {
		if (auxData == EMPTY) {
			throw new IllegalArgumentException("The EMPTY singleton may not be passed as auxilary data");
		}
		entries.incrementAndGet();
		
		while (true) {
			if (needsResize()) {
				resizeArrays();
			}
			int testIndex = scan.getAndIncrement() & (length.get() - 1);
			int prevSeq = seqArray.get().getAndSet(testIndex, DatatableSequenceNumber.UNSTABLE);
			if (prevSeq == DatatableSequenceNumber.UNSTABLE) {
				continue;
			}
			try {
				if (auxArray.get()[testIndex] != EMPTY) {
					continue;
				}
				int idAndData = (((int)id) << 16) | (data & 0xFFFF);
				intArray.get()[testIndex] = idAndData;
				auxArray.get()[testIndex] = auxData;
				return toExternal(testIndex);
			} finally {
				seqArray.get().set(testIndex, DatatableSequenceNumber.get());
			}
		}
	}

	/**
	 * Removes the array elements at the given index.  The array should not be empty at the index in question.<br>
	 * <br>
	 * @param index the index
	 */
	public boolean remove(int index) {
		index = toInternal(index);

		while (true) {
			int prevSeq = seqArray.get().getAndSet(index, DatatableSequenceNumber.UNSTABLE);
			if (prevSeq == DatatableSequenceNumber.UNSTABLE) {
				continue;
			}
			try {
				T current = auxArray.get()[index];
				if (current == EMPTY) {
					return false;
				}
				auxArray.get()[index] = EMPTY;
				entries.decrementAndGet();
				return true;
			} finally {
				seqArray.get().set(index, DatatableSequenceNumber.get());
			}
		}
	}

	/**
	 * Resizes the arrays, if required.
	 * 
	 * The array length is doubled if needsResize returns true.
	 */
	private void resizeArrays() {
		int lockedIndexes = 0;
		try {
			// Lock the first element
			int firstSeq;
			do {
				if (!needsResize()) {
					return;
				}
				firstSeq = seqArray.get().getAndSet(0, DatatableSequenceNumber.UNSTABLE);
			} while (firstSeq == DatatableSequenceNumber.UNSTABLE);
			lockedIndexes++;
			// Once locked, no other thread can do the resize operation

			// Lock the remaining elements
			for (int i = 1; i < length.get(); i++) {
				int seq;
				do {
					if (!needsResize()) {
						return;
					}
					seq = seqArray.get().getAndSet(i, DatatableSequenceNumber.UNSTABLE);
				} while (seq == DatatableSequenceNumber.UNSTABLE);
				lockedIndexes++;
			}

			// Calculate new length
			int newLength = length.get() << 1;
			if (newLength > maxLength || !needsResize()) {
				return;
			}

			// 
			int[] newIntArray = new int[newLength];
			@SuppressWarnings("unchecked")
			T[] newAuxArray = (T[])new Object[newLength];
			AtomicIntegerArray newSeqArray = new AtomicIntegerArray(newLength);
			emptyFill(newAuxArray, null);

			// Copy the state of the current array to the new array
			for (int i = 0; i < length.get(); i++) {
				newIntArray[i] = intArray.get()[i];
				newAuxArray[i] = auxArray.get()[i];
				newSeqArray.set(i, DatatableSequenceNumber.UNSTABLE);
			}

			// Set the top half of the new array to unstable and EMPTY
			for (int i = length.get(); i < newLength; i++) {
				newSeqArray.set(i, DatatableSequenceNumber.UNSTABLE);
				newAuxArray[i] = EMPTY;
			}
			intArray.set(newIntArray);
			auxArray.set(newAuxArray);
			seqArray.set(newSeqArray);

			int oldLength = length.get();

			// Update the length, the array already has been lengthened, so this is safe
			length.set(newLength);

			// Set the top half of the array's sequence number to 0 (from UNSTABLE)
			for (int i = oldLength; i < newLength; i++) {
				if (!seqArray.get().compareAndSet(i, DatatableSequenceNumber.UNSTABLE, DatatableSequenceNumber.get())) {
					throw new IllegalStateException("Element " + i + " + was not locked when released during resizing");
				}
			}
		} finally {
			// Set the bottom half of array to new sequence numbers (from UNSTABLE)
			for (int i = 0; i < lockedIndexes; i++) {
				if (!seqArray.get().compareAndSet(i, DatatableSequenceNumber.UNSTABLE, DatatableSequenceNumber.get())) {
					throw new IllegalStateException("Element " + i + " + was not locked when released during resizing");
				}
			}
		}


	}

	/**
	 * Converts an internal index to an external index.
	 * 
	 * @param internal the internal index
	 * @return the equivalent external index
	 */
	private final int toExternal(int internal) {
		return (internal | reservedMask) & 0xFFFF;
	}

	/**
	 * Converts an external index to an internal index.
	 * 
	 * @param external the external index
	 * @return the equivalent internal index
	 */
	private final int toInternal(int external) {
		return (external & (~reservedMask)) & 0xFFFF;
	}

	/**
	 * Fills an auxiliary array with all empty objects.
	 * 
	 * @param array the array to fill
	 */
	private final void emptyFill(T[] array, AtomicIntegerArray iArray) {
		for (int i = 0; i < array.length; i++) {
			array[i] = EMPTY;
			if (iArray != null) {
				iArray.set(i, DatatableSequenceNumber.get());
			}
		}
	}
	
	/**
	 * Gets the size of the internal arrays
	 * 
	 * @return the size of the arrays
	 */
	public final int getSize() {
		return length.get();
	}
	
	/**
	 * Gets the number of entries in the store
	 * 
	 * @return the size of the arrays
	 */
	public final int getEntries() {
		return entries.get();
	}

	/**
	 * Indicates if the array needs resizing.  An array is considered to need resizing if it is more than 50% full.
	 * 
	 * Once an array has a length of the maximum length, it is never considered in need to resizing.
	 * 
	 * @return true if the array needs to be resized
	 */
	// TODO - add timer that allows for resize downwards
	//      - would need a map to remap the indexes to a lower range though
	private final boolean needsResize() {
		int lengthThreshold = length.get();
		lengthThreshold -= lengthThreshold >> 2;
		return length.get() < maxLength && entries.get() >= lengthThreshold;
	}
	
}