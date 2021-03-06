/*
 * Copyright 2014-2019 the original author or authors.
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

package org.springframework.integration.file.remote.synchronizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.integration.file.HeadDirectoryScanner;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.messaging.MessagingException;

/**
 * @author Gary Russell
 * @author Artem Bilan
 * @author Venil Noronha
 *
 * @since 4.0.4
 *
 */
public class AbstractRemoteFileSynchronizerTests {

	@Test
	public void testRollback() throws Exception {
		final AtomicBoolean failWhenCopyingBar = new AtomicBoolean(true);
		final AtomicInteger count = new AtomicInteger();
		SessionFactory<String> sf = new StringSessionFactory();
		AbstractInboundFileSynchronizer<String> sync = new AbstractInboundFileSynchronizer<String>(sf) {

			@Override
			protected boolean isFile(String file) {
				return true;
			}

			@Override
			protected String getFilename(String file) {
				return file;
			}

			@Override
			protected long getModified(String file) {
				return 0;
			}

			@Override
			protected boolean copyFileToLocalDirectory(String remoteDirectoryPath, String remoteFile,
					File localDirectory, Session<String> session) throws IOException {
				if ("bar".equals(remoteFile) && failWhenCopyingBar.getAndSet(false)) {
					throw new IOException("fail");
				}
				count.incrementAndGet();
				return true;
			}

		};
		sync.setFilter(new AcceptOnceFileListFilter<String>());
		sync.setRemoteDirectory("foo");

		try {
			sync.synchronizeToLocalDirectory(mock(File.class));
			assertThat(count.get()).isEqualTo(1);
			fail("Expected exception");
		}
		catch (MessagingException e) {
			assertThat(e.getCause()).isInstanceOf(MessagingException.class);
			assertThat(e.getCause().getCause()).isInstanceOf(IOException.class);
			assertThat(e.getCause().getCause().getMessage()).isEqualTo("fail");
		}
		sync.synchronizeToLocalDirectory(mock(File.class));
		assertThat(count.get()).isEqualTo(3);
		sync.close();
	}

	@Test
	public void testMaxFetchSizeSynchronizer() throws Exception {
		final AtomicInteger count = new AtomicInteger();
		AbstractInboundFileSynchronizer<String> sync = createLimitingSynchronizer(count);

		sync.synchronizeToLocalDirectory(mock(File.class), 1);
		assertThat(count.get()).isEqualTo(1);
		sync.synchronizeToLocalDirectory(mock(File.class), 1);
		assertThat(count.get()).isEqualTo(2);
		sync.synchronizeToLocalDirectory(mock(File.class), 1);
		assertThat(count.get()).isEqualTo(3);
		sync.close();
	}

	@Test
	public void testMaxFetchSizeSource() throws Exception {
		final AtomicInteger count = new AtomicInteger();
		AbstractInboundFileSynchronizer<String> sync = createLimitingSynchronizer(count);
		AbstractInboundFileSynchronizingMessageSource<String> source = createSource(sync);
		source.afterPropertiesSet();
		source.start();

		source.receive();
		assertThat(count.get()).isEqualTo(1);
		sync.synchronizeToLocalDirectory(mock(File.class), 1);
		source.receive();
		sync.synchronizeToLocalDirectory(mock(File.class), 1);
		source.receive();
		source.stop();
	}

	@Test
	public void testExclusiveScanner() throws Exception {
		final AtomicInteger count = new AtomicInteger();
		AbstractInboundFileSynchronizingMessageSource<String> source = createSource(count);
		source.setScanner(new HeadDirectoryScanner(1));
		source.afterPropertiesSet();
		source.start();
		source.receive();
		assertThat(count.get()).isEqualTo(1);
	}

	@Test
	public void testExclusiveWatchService() throws Exception {
		final AtomicInteger count = new AtomicInteger();
		AbstractInboundFileSynchronizingMessageSource<String> source = createSource(count);
		source.setUseWatchService(true);
		source.afterPropertiesSet();
		source.start();
		source.receive();
		assertThat(count.get()).isEqualTo(1);
	}

	@Test(expected = IllegalStateException.class)
	public void testScannerAndWatchServiceConflict() throws Exception {
		final AtomicInteger count = new AtomicInteger();
		AbstractInboundFileSynchronizingMessageSource<String> source = createSource(count);
		source.setUseWatchService(true);
		source.setScanner(new HeadDirectoryScanner(1));
		source.afterPropertiesSet();
	}

	private AbstractInboundFileSynchronizingMessageSource<String> createSource(AtomicInteger count) {
		return createSource(createLimitingSynchronizer(count));
	}

	private AbstractInboundFileSynchronizingMessageSource<String> createSource(
			AbstractInboundFileSynchronizer<String> sync) {
		AbstractInboundFileSynchronizingMessageSource<String> source =
				new AbstractInboundFileSynchronizingMessageSource<String>(sync) {

					@Override
					public String getComponentType() {
						return "foo";
					}

				};
		source.setMaxFetchSize(1);
		source.setLocalDirectory(new File(System.getProperty("java.io.tmpdir") + File.separator + UUID.randomUUID()));
		source.setAutoCreateLocalDirectory(true);
		source.setBeanFactory(mock(BeanFactory.class));
		source.setBeanName("fooSource");
		return source;
	}

	private AbstractInboundFileSynchronizer<String> createLimitingSynchronizer(final AtomicInteger count) {
		SessionFactory<String> sf = new StringSessionFactory();
		AbstractInboundFileSynchronizer<String> sync = new AbstractInboundFileSynchronizer<String>(sf) {

			@Override
			protected boolean isFile(String file) {
				return true;
			}

			@Override
			protected String getFilename(String file) {
				return file;
			}

			@Override
			protected long getModified(String file) {
				return 0;
			}

			@Override
			protected boolean copyFileToLocalDirectory(String remoteDirectoryPath, String remoteFile,
					File localDirectory, Session<String> session) throws IOException {
				count.incrementAndGet();
				return true;
			}

		};
		sync.setFilter(new AcceptOnceFileListFilter<String>());
		sync.setRemoteDirectory("foo");
		sync.setBeanFactory(mock(BeanFactory.class));
		return sync;
	}

	private class StringSessionFactory implements SessionFactory<String> {

		@Override
		public Session<String> getSession() {
			return new StringSession();
		}

	}

	private class StringSession implements Session<String> {

		@Override
		public boolean remove(String path) throws IOException {
			return true;
		}

		@Override
		public String[] list(String path) throws IOException {
			return new String[] { "foo", "bar", "baz" };
		}

		@Override
		public void read(String source, OutputStream outputStream) throws IOException {
		}

		@Override
		public void write(InputStream inputStream, String destination) throws IOException {
		}

		@Override
		public void append(InputStream inputStream, String destination) throws IOException {
		}

		@Override
		public boolean mkdir(String directory) throws IOException {
			return true;
		}

		@Override
		public boolean rmdir(String directory) throws IOException {
			return true;
		}

		@Override
		public void rename(String pathFrom, String pathTo) throws IOException {
		}

		@Override
		public void close() {
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public boolean exists(String path) throws IOException {
			return true;
		}

		@Override
		public String[] listNames(String path) throws IOException {
			return new String[0];
		}

		@Override
		public InputStream readRaw(String source) throws IOException {
			return null;
		}

		@Override
		public boolean finalizeRaw() throws IOException {
			return true;
		}

		@Override
		public Object getClientInstance() {
			return null;
		}

	}

}
