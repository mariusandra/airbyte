#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#
import os
import sys


def find_base_path(dir_path: str, modules: list):
    init_file = "__init__.py"
    is_python_module = init_file in os.listdir(dir_path)
    if is_python_module:
        up_level_dir = os.path.dirname(dir_path)
        find_base_path(up_level_dir, modules)
    else:
        modules.append(dir_path)


def list_changed_modules(changed_files):
    modules = []
    for file_path in changed_files:
        dir_path = os.path.dirname(file_path)
        find_base_path(dir_path, modules)
    return modules


if __name__ == "__main__":
    changed_modules = list_changed_modules(sys.argv[1:])
    print("Changed Files: ", sys.argv)
    print(",".join(changed_modules))
