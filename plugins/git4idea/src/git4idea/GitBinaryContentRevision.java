/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.BinaryContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsFileUtil;
import git4idea.util.GitFileUtils;
import git4idea.util.GitUtil;
import org.jetbrains.annotations.NotNull;

/**
 * @author irengrig
 *         Date: 12/20/10
 *         Time: 2:57 PM
 */
public class GitBinaryContentRevision extends GitContentRevision implements BinaryContentRevision {
  public GitBinaryContentRevision(@NotNull FilePath file, @NotNull GitRevisionNumber revision, @NotNull Project project) {
    super(file, revision, project, null);
  }

  @Override
  public byte[] getBinaryContent() throws VcsException {
    if (myFile.isDirectory()) {
      return null;
    }
    final VirtualFile root = GitUtil.getGitRoot(myFile);
    return GitFileUtils.getFileContent(myProject, root, myRevision.getRev(), VcsFileUtil.relativePath(root, myFile));
  }
}
