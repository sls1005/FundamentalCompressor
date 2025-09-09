This document describes the principles this project should follow or is following.

### Least privilege

The principle of least privilege, in its original form, suggests that a user or application shouldn't be granted more permission than what they require to perform their work.

We can derive three subprinciples from it:

1. An application should ideally be designed to only perform a task.

    Reason: If it can perform many tasks, it will need to access many things.

2. If there is an alternative method that requires less permission and can achieve the same result, and is not too hard nor too complex to be implemented, that method should be used instead, and the permission is not considered required.

    For example, if you only need to create a file, you don't need to create a directory.

3. Ideally, after the permission is granted to the application, it must be fully utilized, because if it isn't, it is likely that the permission wasn't required.

    For example, when extracting files into a directory, this app writes directly into the selected directory, because if it writes only to a subdirectory, it doesn't need the access to the parent directory, and if it doesn't need to access the parent directory, it shouldn't be granted the permission to access it; thus, we can infer that this app should write directly into the selected directory; and if the users know that this app will write directly into the selected directory, they will choose a less important directory or an empty directory for this app to access, and security is therefore enhanced.

### Flexibility

Ideally, if there is a task *T*, which can be divided into two subtasks, *A* and *B*, a program that can perform *T* should also be able to perform either of *A* or *B* without performing the other subtask.

Exception: when it is hard to implement, e.g., due to the lack of suitable API.

### Correctness

#### Prevent tofu

Incorrectly decoded characters, known as tofu, are awful. There should be a way to prevent them.

Reason: This loss in metadata (i.e. incorrectly decoding file names in an archive; file names are considered metadata that describe their contents) will significantly affect the availability of information, as you will be unable to identify and find files (unless the file names are in ASCII only, which isn't realistic in this modern world, and it is also unrealistic to assume that all archives use UTF-8).

#### Give the control

Machines, especially automated machines, can make mistakes. Therefore, we must give the user a chance to correct them.