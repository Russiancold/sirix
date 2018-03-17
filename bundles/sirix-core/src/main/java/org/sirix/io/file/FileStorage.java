/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.io.file;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.sirix.access.conf.ResourceConfiguration;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Reader;
import org.sirix.io.Storage;
import org.sirix.io.Writer;
import org.sirix.io.bytepipe.ByteHandlePipeline;
import org.sirix.io.bytepipe.ByteHandler;
import org.sirix.page.SerializationType;

/**
 * Factory to provide File access as a backend.
 *
 * @author Sebastian Graf, University of Konstanz.
 *
 */
public final class FileStorage implements Storage {

	/** File name. */
	private static final String FILENAME = "sirix.data";

	/** Instance to storage. */
	private final File mFile;

	/** Byte handler pipeline. */
	private final ByteHandlePipeline mByteHandler;

	/**
	 * Constructor.
	 *
	 * @param file the location of the database
	 * @param byteHandler byte handler pipeline
	 */
	public FileStorage(final ResourceConfiguration resourceConfig) {
		assert resourceConfig != null : "resourceConfig must not be null!";
		mFile = resourceConfig.mPath;
		mByteHandler = resourceConfig.mByteHandler;
	}

	@Override
	public Reader createReader() throws SirixIOException {
		try {
			final Path concreteStorage = createDirectoriesAndFile();

			return new FileReader(new RandomAccessFile(concreteStorage.toFile(), "r"),
					new ByteHandlePipeline(mByteHandler), SerializationType.COMMIT);
		} catch (final IOException e) {
			throw new SirixIOException(e);
		}
	}

	private Path createDirectoriesAndFile() throws IOException {
		final Path concreteStorage = getConcreteStorage();

		if (!Files.exists(concreteStorage)) {
			Files.createDirectories(concreteStorage.getParent());
			Files.createFile(concreteStorage);
		}
		return concreteStorage;
	}

	@Override
	public Writer createWriter() throws SirixIOException {
		try {
			final Path concreteStorage = createDirectoriesAndFile();

			return new FileWriter(new RandomAccessFile(concreteStorage.toFile(), "rw"),
					new ByteHandlePipeline(mByteHandler), SerializationType.COMMIT);
		} catch (final IOException e) {
			throw new SirixIOException(e);
		}
	}

	@Override
	public void close() {
		// not used over here
	}

	/**
	 * Getting concrete storage for this file.
	 *
	 * @return the concrete storage for this database
	 */
	private Path getConcreteStorage() {
		return new File(mFile, new StringBuilder(ResourceConfiguration.Paths.DATA.getFile().getName())
				.append(File.separator).append(FILENAME).toString()).toPath();
	}

	@Override
	public boolean exists() throws SirixIOException {
		final Path storage = getConcreteStorage();
		try {
			return Files.exists(storage) && Files.size(storage) > 0;
		} catch (final IOException e) {
			throw new SirixIOException(e);
		}
	}

	@Override
	public ByteHandler getByteHandler() {
		return mByteHandler;
	}
}
