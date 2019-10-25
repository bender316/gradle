/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.vfs.impl

import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.file.TestFiles
import org.gradle.internal.file.FileType
import org.gradle.internal.hash.HashCode
import org.gradle.internal.snapshot.DirectorySnapshot
import org.gradle.internal.snapshot.FileMetadata
import org.gradle.internal.snapshot.FileSystemLocationSnapshot
import org.gradle.internal.snapshot.MissingFileSnapshot
import org.gradle.internal.snapshot.RegularFileSnapshot
import org.gradle.internal.snapshot.impl.DirectorySnapshotter
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Assume
import org.junit.Rule
import spock.lang.Ignore
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicBoolean

class DefaultFileHierarchySetTest extends Specification {
    @Rule TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    DirectorySnapshotter directorySnapshotter = new DirectorySnapshotter(TestFiles.fileHasher(), new StringInterner())

    def "creates from a single file"() {
        def dir = tmpDir.createDir("dir")
        def child = dir.file("child").createFile()
        expect:
        def set = fileHierarchySet(dir)
        assertDirectorySnapshot(set, dir)
        assertFileSnapshot(set, child)
        !snapshotPresent(set, dir.parentFile)
        !snapshotPresent(set, tmpDir.file("dir2"))
        !snapshotPresent(set, tmpDir.file("d"))
    }

    def "creates from multiple files"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("dir1")
        def dir1Child = dir1.file("child").createFile()
        def dir2 = parent.createDir("common/dir2")
        def dir2Child = dir2.file("child").createFile()
        def dir3 = parent.createDir("common/dir3")
        def dir3Child = dir3.file("child").createFile()

        expect:
        def set = fileHierarchySet([dir1, dir2, dir3])
        [dir1, dir2, dir3].each { File location ->
            assertDirectorySnapshot(set, location)
        }
        [dir1Child, dir2Child, dir3Child].each {
            assertFileSnapshot(set, it)
        }
        assertMissingFileSnapshot(set, dir2.file("some/non-existing/file"))
        !snapshotPresent(set, parent)
        !snapshotPresent(set, dir2.parentFile)
        !snapshotPresent(set, tmpDir.file("dir"))
        !snapshotPresent(set, tmpDir.file("dir12"))
        !snapshotPresent(set, tmpDir.file("common/dir21"))
        set.flatten() == [parent.path, "1:common", "2:dir2", "2:dir3", "1:dir1"]
    }

    def "creates from files where one file is ancestor of the others"() {
        def dir1 = tmpDir.createDir("dir1")
        def dir2 = dir1.createDir("dir2")
        def dir1Child = dir1.file("child")
        dir1Child.createFile()
        def dir2Child = dir2.file("child/some/nested/structure").createFile()
        expect:
        def set = fileHierarchySet([dir2, dir1])
        assertDirectorySnapshot(set, dir1)
        assertDirectorySnapshot(set, dir2)
        assertFileSnapshot(set, dir1Child)
        assertFileSnapshot(set, dir2Child)
        !snapshotPresent(set, dir1.parentFile)
        !snapshotPresent(set, tmpDir.file("dir"))
        !snapshotPresent(set, tmpDir.file("dir12"))
        !snapshotPresent(set, tmpDir.file("dir21"))
        set.flatten() == [dir1.path]
    }

    def "can add dir to empty set"() {
        def empty = FileHierarchySet.EMPTY
        def dir1 = tmpDir.createDir("dir1")
        def dir2 = tmpDir.createDir("dir2")

        def dir1Snapshot = snapshotDir(dir1)
        def dir2Snapshot = snapshotDir(dir2)
        expect:
        def s1 = empty.update(dir1.absolutePath, dir1Snapshot)
        snapshotPresent(s1, dir1)
        !snapshotPresent(s1, dir2)

        def s2 = empty.update(dir2.absolutePath, dir2Snapshot)
        !snapshotPresent(s2, dir1)
        s2.getMetadata(dir2.absolutePath).get() == dir2Snapshot
    }

    def "can add dir to singleton set"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("dir1")
        def dir2 = parent.createDir("dir2")
        def dir3 = parent.createDir("dir3")
        def tooMany = parent.createDir("dir12")
        def tooFew = parent.createDir("dir")
        def child = dir1.createDir("child1")
        def single = fileHierarchySet(dir1)

        expect:
        def s1 = updateDir(single, dir2)
        snapshotPresent(s1, dir1)
        snapshotPresent(s1, child)
        snapshotPresent(s1, dir2)
        !snapshotPresent(s1, dir3)
        !snapshotPresent(s1, tooFew)
        !snapshotPresent(s1, tooMany)
        !snapshotPresent(s1, parent)
        s1.flatten() == [parent.path, "1:dir1", "1:dir2"]

        def s2 = updateDir(single, dir1)
        snapshotPresent(s2, dir1)
        snapshotPresent(s2, child)
        !snapshotPresent(s2, dir2)
        !snapshotPresent(s2, dir3)
        !snapshotPresent(s2, tooFew)
        !snapshotPresent(s2, tooMany)
        !snapshotPresent(s2, parent)
        s2.flatten() == [dir1.path]

        def s3 = updateDir(single, child)
        snapshotPresent(s3, dir1)
        snapshotPresent(s3, child)
        !snapshotPresent(s3, dir2)
        !snapshotPresent(s3, dir3)
        !snapshotPresent(s3, tooFew)
        !snapshotPresent(s3, tooMany)
        !snapshotPresent(s3, parent)
        s3.flatten() == [dir1.path]

        def s4 = updateDir(single, parent)
        snapshotPresent(s4, dir1)
        snapshotPresent(s4, child)
        snapshotPresent(s4, dir2)
        snapshotPresent(s4, dir3)
        snapshotPresent(s4, parent)
        s4.flatten() == [parent.path]

        def s5 = updateDir(single, tooFew)
        snapshotPresent(s5, dir1)
        snapshotPresent(s5, child)
        snapshotPresent(s5, tooFew)
        !snapshotPresent(s5, dir2)
        !snapshotPresent(s5, tooMany)
        !snapshotPresent(s5, parent)
        s5.flatten() == [parent.path, "1:dir", "1:dir1"]

        def s6 = updateDir(single, tooMany)
        snapshotPresent(s6, dir1)
        snapshotPresent(s6, child)
        snapshotPresent(s6, tooMany)
        !snapshotPresent(s6, dir2)
        !snapshotPresent(s6, tooFew)
        !snapshotPresent(s6, parent)
        s6.flatten() == [parent.path, "1:dir1", "1:dir12"]
    }

    def "can add dir to multi set"() {
        def parentParent = tmpDir.createDir()
        def parent = parentParent.createDir("parent")
        def dir1 = parent.createDir("dir1")
        def dir2 = parent.createDir("dir2")
        def dir3 = parent.createDir("dir3")
        def other = parent.createDir("dir4")
        def child = dir1.createDir("child1")
        def multi = fileHierarchySet([dir1, dir2])

        expect:
        def s1 = updateDir(multi, dir3)
        snapshotPresent(s1, dir1)
        snapshotPresent(s1, child)
        snapshotPresent(s1, dir2)
        snapshotPresent(s1, dir3)
        !snapshotPresent(s1, other)
        !snapshotPresent(s1, parent)
        s1.flatten() == [parent.path, "1:dir1", "1:dir2", "1:dir3"]

        def s2 = updateDir(multi, dir2)
        snapshotPresent(s2, dir1)
        snapshotPresent(s2, child)
        snapshotPresent(s2, dir2)
        !snapshotPresent(s2, dir3)
        !snapshotPresent(s2, other)
        !snapshotPresent(s2, parent)
        s2.flatten() == [parent.path, "1:dir1", "1:dir2"]

        def s3 = updateDir(multi, child)
        snapshotPresent(s3, dir1)
        snapshotPresent(s3, child)
        snapshotPresent(s3, dir2)
        !snapshotPresent(s3, dir3)
        !snapshotPresent(s3, other)
        !snapshotPresent(s3, parent)
        s3.flatten() == [parent.path, "1:dir1", "1:dir2"]

        def s4 = updateDir(multi, parent)
        snapshotPresent(s4, dir1)
        snapshotPresent(s4, child)
        snapshotPresent(s4, dir2)
        snapshotPresent(s4, other)
        snapshotPresent(s4, parent)
        s4.flatten() == [parent.path]
    }

    def "splits and merges prefixes as directories are added"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("dir1")
        def dir1dir2 = dir1.createDir("dir2")
        def dir1dir2dir3 = dir1dir2.createDir("dir3")
        def dir1dir2dir4 = dir1dir2.createDir("dir4")
        def dir1dir5 = dir1.createDir("dir5/and/more")
        def dir6 = parent.createDir("dir6")

        expect:
        def s1 = fileHierarchySet([dir1dir2dir3, dir1dir5])
        s1.flatten() == [dir1.path, "1:dir2/dir3", "1:dir5/and/more"]

        def s2 = updateDir(s1, dir1dir2dir4)
        s2.flatten() == [dir1.path, "1:dir2", "2:dir3", "2:dir4", "1:dir5/and/more"]

        def s3 = updateDir(s2, dir6)
        s3.flatten() == [parent.path, "1:dir1", "2:dir2", "3:dir3", "3:dir4", "2:dir5/and/more", "1:dir6"]

        def s4 = updateDir(s3, dir1dir2)
        s4.flatten() == [parent.path, "1:dir1", "2:dir2", "2:dir5/and/more", "1:dir6"]

        def s5 = updateDir(s4, dir1)
        s5.flatten() == [parent.path, "1:dir1", "1:dir6"]

        def s6 = updateDir(s3, dir1)
        s6.flatten() == [parent.path, "1:dir1", "1:dir6"]

        def s7 = updateDir(s3, parent)
        s7.flatten() == [parent.path]
    }

    def "can add directory snapshot in between to forking points"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("sub/dir1")
        def dir2 = parent.createDir("sub/dir2")
        def dir3 = parent.createDir("dir3")

        when:
        def set = fileHierarchySet([dir1, dir2, dir3])
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir2)
        snapshotPresent(set, dir3)
    }

    def "can update existing snapshots"() {
        def dir = tmpDir.createDir("dir")
        def child = dir.createFile("child")
        def set = fileHierarchySet(dir)

        when:
        child.text = "updated"
        set = updateDir(invalidate(set, child), dir)
        then:
        assertDirectorySnapshot(set, dir)
        assertFileSnapshot(set, child)
    }

    def "can update file snapshot with sub-dir snapshot"() {
        def dir = tmpDir.createFile("dir")
        def child = dir.file("sub/child")
        def set = fileHierarchySet(dir)

        when:
        dir.delete()
        child.text = "created"
        set = updateDir(invalidate(set, child), dir.file("sub"))
        then:
        assertDirectorySnapshot(set, dir.file("sub"))
        assertFileSnapshot(set, child)
    }

    def "can add new parent"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("sub/dir1")
        def dir2 = parent.createDir("sub/dir2")

        when:
        def set = fileHierarchySet([dir1, dir2, parent])
        then:
        snapshotPresent(set, parent)
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir2)
    }

    def "adding a snapshot in a known directory is ignored"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("dir1")
        def fileInDir = dir1.createFile("file1")
        def setWithDir1 = fileHierarchySet(dir1)

        when:
        def subDir = dir1.file("sub").createDir()
        def set = updateDir(setWithDir1, subDir)
        then:
        snapshotPresent(set, subDir)
        snapshotPresent(set, fileInDir)
        snapshotPresent(set, dir1)
    }

    def "returns missing snapshots for children of files"() {
        def existing = tmpDir.createFile("existing")
        def missing = tmpDir.file("missing")

        when:
        def set = fileHierarchySet([existing, missing])
        then:
        assertFileSnapshot(set, existing)
        assertMissingFileSnapshot(set, missing)
        assertMissingFileSnapshot(set, existing.file("some/sub/path"))
        assertMissingFileSnapshot(set, missing.file("some/sub/path"))
    }

    def "can invalidate paths"() {
        def parent = tmpDir.createDir()
        def dir1 = parent.createDir("dir1")
        def dir2 = parent.createDir("sub/more/dir2")
        def dir2File = dir2.file("existing").createFile()
        def dir2FileSibling = dir2.file("sibling").createFile()
        def dir3 = parent.createDir("sub/more/dir3")
        def fullSet = fileHierarchySet([dir1, dir2, dir3])

        when:
        def set = invalidate(fullSet, dir2)
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir3)
        !snapshotPresent(set, dir2)

        when:
        set = invalidate(fullSet, dir2.parentFile)
        then:
        snapshotPresent(set, dir1)
        !snapshotPresent(set, dir2)
        !snapshotPresent(set, dir3)

        when:
        set = invalidate(fullSet, dir2.file("non-exisiting"))
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir3)
        snapshotPresent(set, dir2File)
        snapshotPresent(set, dir2FileSibling)
        !snapshotPresent(set, dir2)

        when:
        set = invalidate(fullSet, dir2File)
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir3)
        snapshotPresent(set, dir2FileSibling)
        !snapshotPresent(set, dir2)
        !snapshotPresent(set, dir2File)

        when:
        set = invalidate(fullSet, parent.file("sub/more/dir4"))
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir2)
        snapshotPresent(set, dir3)
        !snapshotPresent(set, parent.file("sub/more/dir4"))

        when:
        set = invalidate(fullSet, parent.file("sub/else"))
        then:
        snapshotPresent(set, dir1)
        snapshotPresent(set, dir2)
        snapshotPresent(set, dir3)
        !snapshotPresent(set, parent.file("sub/else"))
    }

    def "can invalidate child of file"() {
        def file = tmpDir.createFile("some/dir/file.txt")
        def set = fileHierarchySet(file)

        when:
        set = invalidate(set, file.file("child"))
        then:
        !snapshotPresent(set, file)
    }

    def "can invalidate branching off of snapshot"() {
        def file = tmpDir.createDir("some/sub/dir")
        def invalidatedLocation = tmpDir.file("some/other/file")
        def set = fileHierarchySet(file)

        when:
        set = invalidate(set, invalidatedLocation)
        then:
        snapshotPresent(set, file)
        !snapshotPresent(set, invalidatedLocation)
    }

    def "root is handled correctly"() {
        Assume.assumeTrue("Root is only defined for the file separator '/'", File.separator == '/')

        when:
        def set = FileHierarchySet.EMPTY.update("/", new DirectorySnapshot("/", "", [new RegularFileSnapshot("/root.txt", "root.txt", HashCode.fromInt(1234), new FileMetadata(1, 1))], HashCode.fromInt(1111)))
        then:
        set.getMetadata("/root.txt").get().type == FileType.RegularFile

        when:
        set = set.invalidate("/root.txt").update("/", new DirectorySnapshot("/", "", [new RegularFileSnapshot("/base.txt", "base.txt", HashCode.fromInt(1234), new FileMetadata(1, 1))], HashCode.fromInt(2222)))
        then:
        set.getMetadata("/base.txt").get().type == FileType.RegularFile
    }

    def "updates are inserted sorted"() {
        def parent = tmpDir.createDir()
        def childA = parent.file("a")
        def childB = parent.file("b")
        def childB1 = parent.file("b1")

        when:
        def set = fileHierarchySet([childA, childB, childB1])
        then:
        set.flatten() == [parent.absolutePath, "1:a", "1:b", "1:b1"]

        when:
        set = fileHierarchySet([parent.file("a/b/c"), parent.file("a/b-c/c"), parent.file("a/b/d")])
        then:
        set.flatten() == [childA.absolutePath, "1:b", "2:c", "2:d", "1:b-c/c"]

        when:
        set = fileHierarchySet([parent.file("a/b/c/a"), parent.file("a/b/c/b"), parent.file("a/b-c/c"), parent.file("a/b/d")])
        then:
        set.flatten() == [childA.absolutePath, "1:b", "2:c", "3:a", "3:b", "2:d", "1:b-c/c"]
    }

    @Ignore("Ignore test for now, there is a bug on Windows")
    def "can add to completely different paths"() {
        def firstPath = File.separator == "/"
            ? "/var/log"
            : "C:\\Windows\\log"
        def secondPath = File.separator == "/"
            ? "/usr/bin"
            : "D:\\Users\\bin"

        def set = FileHierarchySet.EMPTY.update(firstPath, new DirectorySnapshot(firstPath, "log", [new RegularFileSnapshot("${firstPath}${File.separator}root.txt", "root.txt", HashCode.fromInt(1234), new FileMetadata(1, 1))], HashCode.fromInt(1111)))
        .update(secondPath, new DirectorySnapshot(secondPath, "bin", [new RegularFileSnapshot("${secondPath}${File.separator}root.txt", "root.txt", HashCode.fromInt(1234), new FileMetadata(1, 1))], HashCode.fromInt(1111)))

        expect:
        set.getMetadata(firstPath).present
        set.getMetadata(secondPath).present
    }

    private FileSystemLocationSnapshot snapshotDir(File dir) {
        directorySnapshotter.snapshot(dir.absolutePath, null, new AtomicBoolean(false))
    }

    private static FileSystemLocationSnapshot snapshotFile(File file) {
        if (!file.exists()) {
            return new MissingFileSnapshot(file.absolutePath, file.name)
        }
        return new RegularFileSnapshot(file.absolutePath, file.name, TestFiles.fileHasher().hash(file), FileMetadata.from(TestFiles.fileSystem().stat(file)))
    }

    static HashCode hashFile(File file) {
        TestFiles.fileHasher().hash(file)
    }
    private static void assertFileSnapshot(FileHierarchySet set, File location) {
        def snapshot = set.getMetadata(location.absolutePath).get()
        assert snapshot.absolutePath == location.absolutePath
        assert snapshot.name == location.name
        assert snapshot.type == FileType.RegularFile
        assert snapshot.hash == hashFile(location)
    }

    private void assertDirectorySnapshot(FileHierarchySet set, File location) {
        def snapshot = set.getMetadata(location.absolutePath).get()
        assert snapshot.absolutePath == location.absolutePath
        assert snapshot.name == location.name
        assert snapshot.type == FileType.Directory
        assert snapshot.hash == snapshotDir(location).hash
    }

    private static void assertMissingFileSnapshot(FileHierarchySet set, File location) {
        def snapshot = set.getMetadata(location.absolutePath).get()
        assert snapshot.absolutePath == location.absolutePath
        assert snapshot.name == location.name
        assert snapshot.type == FileType.Missing
    }

    private static boolean snapshotPresent(FileHierarchySet set, File location) {
        set.getMetadata(location.absolutePath).present
    }

    private static FileHierarchySet invalidate(FileHierarchySet set, File location) {
        set.invalidate(location.absolutePath)
    }

    private FileHierarchySet fileHierarchySet(File location) {
        FileHierarchySet.EMPTY.update(location.absolutePath, location.directory ? snapshotDir(location) : snapshotFile(location))
    }

    private FileHierarchySet fileHierarchySet(Iterable<? extends File> locations) {
        FileHierarchySet set = FileHierarchySet.EMPTY
        for (File location : locations) {
            set = set.update(location.absolutePath, location.directory ? snapshotDir(location) : snapshotFile(location))
        }
        return set
    }

    private FileHierarchySet updateDir(FileHierarchySet set, File dir) {
        set.update(dir.absolutePath, snapshotDir(dir))
    }
}
